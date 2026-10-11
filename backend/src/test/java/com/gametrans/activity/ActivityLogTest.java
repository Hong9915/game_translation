package com.gametrans.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.ProjectRepository;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 기록 지점: 어떤 행동이 activity를 만들고 어떤 리비전이 거기 매달리는가. */
class ActivityLogTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired ProjectRepository projects;
    @Autowired RevisionService revisions;
    @Autowired ActivityService activities;
    @Autowired ActivityRepository repo;
    @Autowired com.gametrans.review.BulkFixWriter writer;
    @Autowired com.gametrans.job.JobService jobs;

    /** withExistingKo(): Skill 시트, 2행 원문 正义裁决 / 기존 한국어 "정의의 심판". */
    long normalized(String game) throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE)).andExpect(status().isOk());
        return pid;
    }

    /** withExistingKoSameSourceTwice(): 같은 시트 안에 2행과 6행의 원문이 똑같다. */
    long normalizedSameSourceTwice(String game) throws Exception {
        long pid = TestProjects.uploadExistingKoSameSourceTwice(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE)).andExpect(status().isOk());
        return pid;
    }

    Segment row(long pid, int rowNo) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getRowNo() == rowNo).findFirst().orElseThrow();
    }

    long reviewer(String name) throws Exception {
        String body = mvc.perform(post("/api/reviewers").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body).read("$.id", Long.class);
    }

    List<Activity> ofProject(long pid) { return repo.findByProjectIdOrderByIdDesc(pid); }

    List<TranslationRevision> revisionsOf(Activity a) { return revisions.byActivityId(a.getId()); }

    @Test
    void startMakesARowAndRevisionsCanHangOnIt() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Segment first = row(pid, 2);

        Long aid = activities.start(pid, Activity.Kind.EDIT, null, Map.of("segmentId", first.getId()));
        revisions.add(first.getId(), "정의의 판결", TranslationRevision.Author.HUMAN, null, aid);

        Activity a = repo.findById(aid).orElseThrow();
        assertThat(a.getProjectId()).isEqualTo(pid);
        assertThat(a.getGameId()).isEqualTo(projects.findById(pid).orElseThrow().getGame().getId());
        assertThat(a.getKind()).isEqualTo(Activity.Kind.EDIT);
        assertThat(((Number) a.getDetail().get("segmentId")).longValue()).isEqualTo(first.getId());
        assertThat(revisionsOf(a)).singleElement().satisfies(r -> {
            assertThat(r.getText()).isEqualTo("정의의 판결");
            assertThat(r.getActivityId()).isEqualTo(aid);
        });
    }

    @Test
    void theFourArgAddLeavesActivityNull() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Segment first = row(pid, 2);
        TranslationRevision r = revisions.add(first.getId(), "정의의 판결", TranslationRevision.Author.HUMAN, null);
        assertThat(r.getActivityId()).isNull();
    }

    void save(long sid, String text, Long reviewerId) throws Exception {
        var req = put("/api/segments/{id}/translation", sid).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + text + "\"}");
        if (reviewerId != null) req.header("X-Reviewer-Id", reviewerId);
        mvc.perform(req).andExpect(status().isOk());
    }

    @Test
    void aHumanEditLogsOneActivityWithThePropagatedRowsOnIt() throws Exception {
        long pid = normalizedSameSourceTwice(TestProjects.freshGame());
        long rid = reviewer("홍길동 " + pid);
        Segment r2 = row(pid, 2), r6 = row(pid, 6);

        save(r2.getId(), "정의의 판결", rid);

        assertThat(ofProject(pid)).singleElement().satisfies(a -> {
            assertThat(a.getKind()).isEqualTo(Activity.Kind.EDIT);
            assertThat(a.getReviewerId()).isEqualTo(rid);
            assertThat(((Number) a.getDetail().get("segmentId")).longValue()).isEqualTo(r2.getId());
            assertThat(revisionsOf(a)).extracting(TranslationRevision::getSegmentId).containsExactlyInAnyOrder(r2.getId(), r6.getId());
        });
    }

    @Test
    void savingTheSameTextLogsNothing() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        save(row(pid, 2).getId(), "정의의 심판", null);
        assertThat(ofProject(pid)).isEmpty();
    }

    /** Review Focus 1: 대상 행은 그대로인데 같은 원문 행만 달라서 따라 바뀌면, 활동은 생기되 대상 행 리비전은 없다. */
    @Test
    void savingTheSameTextStillLogsPropagatedRows() throws Exception {
        long pid = normalizedSameSourceTwice(TestProjects.freshGame());
        Segment r2 = row(pid, 2), r6 = row(pid, 6);
        revisions.add(r6.getId(), "다른 번역", TranslationRevision.Author.HUMAN, null);

        save(r2.getId(), "정의의 심판", null);

        assertThat(ofProject(pid)).singleElement().satisfies(a ->
            assertThat(revisionsOf(a)).extracting(TranslationRevision::getSegmentId).containsExactly(r6.getId()));
    }

    @Test
    void theThreeArgWriteLeavesActivityNull() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Segment first = row(pid, 2);
        writer.write(List.of(first), s -> "정의의 판결", null);
        assertThat(revisions.current(first.getId()).orElseThrow().getActivityId()).isNull();
        assertThat(ofProject(pid)).isEmpty();
    }

    @Test
    void aShortenJobLogsOneActivityAtStart() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        long rid = reviewer("줄이기 " + pid);

        mvc.perform(post("/api/projects/{id}/shorten", pid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ratio\":10,\"sheet\":\"Skill\"}").header("X-Reviewer-Id", rid)).andExpect(status().isOk());

        assertThat(ofProject(pid)).singleElement().satisfies(a -> {
            assertThat(a.getKind()).isEqualTo(Activity.Kind.SHORTEN);
            assertThat(a.getReviewerId()).isEqualTo(rid);
            assertThat(a.getDetail()).containsEntry("sheet", "Skill");
        });
    }

    @Test
    void aFixTermsJobLogsOneActivityAtStart() throws Exception {
        long pid = normalized(TestProjects.freshGame());

        mvc.perform(post("/api/projects/{id}/fix-terms", pid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ratio\":10}")).andExpect(status().isOk());

        assertThat(ofProject(pid)).singleElement().satisfies(a -> {
            assertThat(a.getKind()).isEqualTo(Activity.Kind.FIX_TERMS);
            assertThat(a.getReviewerId()).isNull();
            assertThat(a.getDetail()).doesNotContainKey("sheet");
        });
    }

    /** 한 행 다시 번역은 FakeLlmClient가 "KO:원문"을 돌려주므로 따로 준비할 것이 없다. */
    @Test
    void aRowRetranslateLogsRetranslateWithItsRevision() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Segment first = row(pid, 2);

        mvc.perform(post("/api/segments/{id}/translate", first.getId()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        assertThat(ofProject(pid)).singleElement().satisfies(a -> {
            assertThat(a.getKind()).isEqualTo(Activity.Kind.RETRANSLATE);
            assertThat(((Number) a.getDetail().get("segmentId")).longValue()).isEqualTo(first.getId());
            assertThat(revisionsOf(a)).singleElement().satisfies(r -> assertThat(r.getAuthor()).isEqualTo(TranslationRevision.Author.LLM));
        });
    }

    Long gameOf(long pid) { return projects.findById(pid).orElseThrow().getGame().getId(); }

    @Test
    void addingAGlossaryEntryLogsAnEditForTheGameAndAnApplyPerChangedProject() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalized(game);
        long b = normalized(game);
        long rid = reviewer("단어장 " + a);
        Long gid = gameOf(a);
        // b의 2행을 미리 다른 말로 바꿔 두면 "심판"이 없어 b는 안 바뀐다 → GLOSSARY_APPLY는 a에만 생긴다.
        revisions.add(row(b, 2).getId(), "정의의 판결", TranslationRevision.Author.HUMAN, null);

        mvc.perform(post("/api/games/{g}/glossary", gid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"KO\",\"matchMode\":\"PARTIAL\",\"find\":\"심판\",\"ko\":\"판결\"}")
                .header("X-Reviewer-Id", rid)).andExpect(status().isOk());

        assertThat(repo.findByGameIdAndProjectIdIsNullOrderByIdDesc(gid)).singleElement().satisfies(e -> {
            assertThat(e.getKind()).isEqualTo(Activity.Kind.GLOSSARY_EDIT);
            assertThat(e.getReviewerId()).isEqualTo(rid);
            assertThat(e.getDetail()).containsEntry("action", "ADD").containsEntry("kind", "KO").containsEntry("find", "심판").containsEntry("ko", "판결");
            assertThat(e.getDetail().get("oldKo")).isNull();
        });
        assertThat(ofProject(a)).singleElement().satisfies(x -> {
            assertThat(x.getKind()).isEqualTo(Activity.Kind.GLOSSARY_APPLY);
            assertThat(revisionsOf(x)).extracting(TranslationRevision::getSegmentId).containsExactly(row(a, 2).getId());
        });
        assertThat(ofProject(b)).isEmpty();
    }

    @Test
    void updatingDeletingAndSharingLogEdits() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Long gid = gameOf(pid);
        String body = mvc.perform(post("/api/games/{g}/glossary", gid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"KO\",\"matchMode\":\"WHOLE\",\"find\":\"없는말\",\"ko\":\"없는말2\"}")).andReturn().getResponse().getContentAsString();
        long eid = JsonPath.parse(body).read("$.entry.id", Long.class);

        mvc.perform(put("/api/glossary/{id}", eid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"KO\",\"matchMode\":\"WHOLE\",\"find\":\"없는말\",\"ko\":\"없는말3\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/glossary/{id}/share", eid)).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/glossary/{id}", eid)).andExpect(status().isNoContent());

        assertThat(repo.findByGameIdAndProjectIdIsNullOrderByIdDesc(gid)).extracting(x -> x.getDetail().get("action"))
                .containsExactly("DELETE", "SHARE", "UPDATE", "ADD");
    }

    @Test
    void applyAllLogsOneApplyPerProjectThatChanged() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalized(game);
        long b = normalized(game);
        Long gid = gameOf(a);
        // 항목은 이미 있고(추가 시점에 a·b 둘 다 바뀜), 그 뒤 a만 옛 말로 되돌려 두면 전체 다시 적용은 a만 바꾼다.
        mvc.perform(post("/api/games/{g}/glossary", gid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"KO\",\"matchMode\":\"PARTIAL\",\"find\":\"심판\",\"ko\":\"판결\"}")).andExpect(status().isOk());
        revisions.add(row(a, 2).getId(), "정의의 심판", TranslationRevision.Author.HUMAN, null);
        int beforeA = ofProject(a).size(), beforeB = ofProject(b).size();

        mvc.perform(post("/api/games/{g}/glossary/apply", gid)).andExpect(status().isOk());

        assertThat(ofProject(a)).hasSize(beforeA + 1);
        assertThat(ofProject(a).get(0).getKind()).isEqualTo(Activity.Kind.GLOSSARY_APPLY);
        assertThat(ofProject(b)).hasSize(beforeB);
    }

    /** 리뷰 finding 1: 이미 도는 작업이 있어 거절된 작업은 변경 내역을 남기면 안 된다. */
    @Test
    void aRefusedJobLogsNothing() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        jobs.create(pid, com.gametrans.job.TranslationJob.Kind.SHORTEN);   // RUNNING 상태로 남는다

        mvc.perform(post("/api/projects/{id}/shorten", pid).contentType(MediaType.APPLICATION_JSON).content("{\"ratio\":10}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/projects/{id}/fix-terms", pid).contentType(MediaType.APPLICATION_JSON).content("{\"ratio\":10}"))
                .andExpect(status().isConflict());

        assertThat(ofProject(pid)).isEmpty();
    }

    /** 리뷰 finding 2: 지워진 검토자 id(브라우저에 남은 옛 값)는 FK 위반 500이 아니라 익명으로 기록한다. */
    @Test
    void anUnknownReviewerIdIsLoggedAsAnonymous() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Long gid = gameOf(pid);

        mvc.perform(post("/api/projects/{id}/shorten", pid).contentType(MediaType.APPLICATION_JSON).content("{\"ratio\":10}")
                .header("X-Reviewer-Id", 999_999)).andExpect(status().isOk());
        // 단어장 추가는 created_by FK 때문에 원래부터 모르는 id를 거절한다(이 브랜치 밖). 삭제·공유는 id를 그냥 받았으므로 여기서 확인한다.
        String body = mvc.perform(post("/api/games/{g}/glossary", gid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"KO\",\"matchMode\":\"WHOLE\",\"find\":\"없는말\",\"ko\":\"없는말2\"}")).andReturn().getResponse().getContentAsString();
        long eid = JsonPath.parse(body).read("$.entry.id", Long.class);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/glossary/{id}", eid)
                .header("X-Reviewer-Id", 999_999)).andExpect(status().isNoContent());

        assertThat(ofProject(pid)).singleElement().satisfies(a -> assertThat(a.getReviewerId()).isNull());
        assertThat(repo.findByGameIdAndProjectIdIsNullOrderByIdDesc(gid)).hasSize(2)
                .allSatisfy(a -> assertThat(a.getReviewerId()).isNull());
    }

    @Test
    void lazyPerProjectMakesOneRowPerProjectOnlyWhenAsked() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalized(game);
        long b = normalized(game);

        var f = activities.lazyPerProject(Activity.Kind.GLOSSARY_APPLY, null, Map.of());
        assertThat(ofProject(a)).isEmpty();
        Long first = f.apply(a);
        assertThat(f.apply(a)).isEqualTo(first);
        assertThat(ofProject(a)).hasSize(1);
        assertThat(ofProject(b)).isEmpty();
        assertThat(f.apply(b)).isNotEqualTo(first);
        assertThat(ofProject(b)).hasSize(1);
    }
}
