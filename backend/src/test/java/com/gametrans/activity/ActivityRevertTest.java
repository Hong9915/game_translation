package com.gametrans.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 변경 내역에서 되돌리기: 그 항목이 건드린 행만 이전 본문으로 새 판을 쓴다(전파 없음). 그 사이 바뀐 행·확정 행·첫 판 행은 건너뛴다.
 * 되돌리기 자체가 REVERT 항목으로 남아 다시 되돌릴 수 있다.
 */
class ActivityRevertTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired ProjectRepository projects;
    @Autowired RevisionService revisions;
    @Autowired ActivityRepository repo;

    /** withExistingKoSameSourceTwice(): Skill 시트, 2행과 6행의 원문이 같고 기존 한국어는 "정의의 심판". 5행은 기존 한국어가 비어 리비전이 없다. */
    long normalized(String game) throws Exception {
        long pid = TestProjects.uploadExistingKoSameSourceTwice(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE)).andExpect(status().isOk());
        return pid;
    }

    Segment row(long pid, int rowNo) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getRowNo() == rowNo).findFirst().orElseThrow();
    }

    String text(Segment s) { return revisions.current(s.getId()).map(TranslationRevision::getText).orElse(null); }

    void save(long sid, String text) throws Exception {
        mvc.perform(put("/api/segments/{id}/translation", sid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}")).andExpect(status().isOk());
    }

    long latestActivity(long pid) { return repo.findByProjectIdOrderByIdDesc(pid).get(0).getId(); }

    long reviewer(String name) throws Exception {
        String body = mvc.perform(post("/api/reviewers").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body).read("$.id", Long.class);
    }

    @Test
    void revertsAnEditAndItsPropagatedRowsAndLogsARevert() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        long rid = reviewer("되돌림 " + pid);
        Segment r2 = row(pid, 2), r6 = row(pid, 6);
        save(r2.getId(), "정의의 판결");
        long edit = latestActivity(pid);

        mvc.perform(post("/api/activity/{id}/revert", edit).header("X-Reviewer-Id", rid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reverted").value(2))
                .andExpect(jsonPath("$.skipped").value(0));

        assertThat(text(r2)).isEqualTo("정의의 심판");
        assertThat(text(r6)).isEqualTo("정의의 심판");
        List<Activity> all = repo.findByProjectIdOrderByIdDesc(pid);
        assertThat(all).hasSize(2);
        Activity revert = all.get(0);
        assertThat(revert.getKind()).isEqualTo(Activity.Kind.REVERT);
        assertThat(revert.getReviewerId()).isEqualTo(rid);
        assertThat(((Number) revert.getDetail().get("revertOf")).longValue()).isEqualTo(edit);
        assertThat(revisions.byActivityId(revert.getId())).extracting(TranslationRevision::getSegmentId)
                .containsExactlyInAnyOrder(r2.getId(), r6.getId());
        // 되돌린 판도 사람이 쓴 판이다 — 이력에서 지운 게 아니다.
        assertThat(revisions.current(r2.getId()).orElseThrow().getAuthor()).isEqualTo(TranslationRevision.Author.HUMAN);
    }

    @Test
    void skipsRowsChangedSinceAndReview2Rows() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Segment r2 = row(pid, 2), r6 = row(pid, 6);
        save(r2.getId(), "정의의 판결");
        long edit = latestActivity(pid);
        // 6행은 확정, 2행은 그 사이 한 번 더 고친다(확정 행은 전파에서 빠지므로 6행은 그대로 "정의의 판결").
        mvc.perform(put("/api/segments/{id}/marks", r6.getId()).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"))
                .andExpect(status().isOk());
        save(r2.getId(), "셋째 판");

        mvc.perform(post("/api/activity/{id}/revert", edit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reverted").value(0))
                .andExpect(jsonPath("$.skipped").value(2));

        assertThat(text(r2)).isEqualTo("셋째 판");
        assertThat(text(r6)).isEqualTo("정의의 판결");
        assertThat(repo.findByProjectIdOrderByIdDesc(pid)).extracting(Activity::getKind).doesNotContain(Activity.Kind.REVERT);
    }

    @Test
    void aFirstRevisionCannotBeRevertedAndCountsAsSkipped() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Segment empty = row(pid, 5);
        assertThat(revisions.current(empty.getId())).isEmpty();
        save(empty.getId(), "새로 쓴 글");
        long edit = latestActivity(pid);

        mvc.perform(post("/api/activity/{id}/revert", edit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reverted").value(0))
                .andExpect(jsonPath("$.skipped").value(1));
        assertThat(text(empty)).isEqualTo("새로 쓴 글");
    }

    @Test
    void revertingARevertRestoresTheEdit() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Segment r2 = row(pid, 2);
        save(r2.getId(), "정의의 판결");
        long edit = latestActivity(pid);
        mvc.perform(post("/api/activity/{id}/revert", edit)).andExpect(status().isOk());
        long revert = latestActivity(pid);
        assertThat(revert).isNotEqualTo(edit);

        mvc.perform(post("/api/activity/{id}/revert", revert)).andExpect(status().isOk()).andExpect(jsonPath("$.reverted").value(2));

        assertThat(text(r2)).isEqualTo("정의의 판결");
        assertThat(text(row(pid, 6))).isEqualTo("정의의 판결");
    }

    @Test
    void aGlossaryApplyRevertsItsRowsButLeavesTheEntry() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Long gid = projects.findById(pid).orElseThrow().getGame().getId();
        Segment r2 = row(pid, 2);
        mvc.perform(post("/api/games/{g}/glossary", gid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"KO\",\"matchMode\":\"PARTIAL\",\"find\":\"심판\",\"ko\":\"판결\"}")).andExpect(status().isOk());
        assertThat(text(r2)).isEqualTo("정의의 판결");
        Activity apply = repo.findByProjectIdOrderByIdDesc(pid).get(0);
        assertThat(apply.getKind()).isEqualTo(Activity.Kind.GLOSSARY_APPLY);

        mvc.perform(post("/api/activity/{id}/revert", apply.getId())).andExpect(status().isOk()).andExpect(jsonPath("$.reverted").value(2));

        assertThat(text(r2)).isEqualTo("정의의 심판");
        // 항목은 그대로 — 전체 다시 적용을 누르면 다시 바뀐다.
        mvc.perform(post("/api/games/{g}/glossary/apply", gid)).andExpect(status().isOk());
        assertThat(text(r2)).isEqualTo("정의의 판결");
    }

    @Test
    void aGlossaryEditCannotBeReverted() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Long gid = projects.findById(pid).orElseThrow().getGame().getId();
        mvc.perform(post("/api/games/{g}/glossary", gid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"KO\",\"matchMode\":\"WHOLE\",\"find\":\"없는말\",\"ko\":\"없는말2\"}")).andExpect(status().isOk());
        long edit = repo.findByGameIdAndProjectIdIsNullOrderByIdDesc(gid).get(0).getId();

        mvc.perform(post("/api/activity/{id}/revert", edit)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/activity/{id}/revert", 999_999)).andExpect(status().isNotFound());
    }
}
