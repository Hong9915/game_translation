package com.gametrans.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 피드 조회(설계 §3): 최신순·커서·행 수·대표 행의 전후 본문·순번·게임 단위 항목 섞기. */
class ActivityFeedTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired ProjectRepository projects;
    @Autowired RevisionService revisions;
    @Autowired ActivityRepository repo;

    long normalizedSameSourceTwice(String game) throws Exception {
        long pid = TestProjects.uploadExistingKoSameSourceTwice(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE)).andExpect(status().isOk());
        return pid;
    }

    List<Segment> rows(long pid) { return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid); }
    Segment row(long pid, int rowNo) { return rows(pid).stream().filter(s -> s.getRowNo() == rowNo).findFirst().orElseThrow(); }

    long reviewer(String name) throws Exception {
        String body = mvc.perform(post("/api/reviewers").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body).read("$.id", Long.class);
    }

    void save(long sid, String text, Long reviewerId) throws Exception {
        var req = put("/api/segments/{id}/translation", sid).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + text + "\"}");
        if (reviewerId != null) req.header("X-Reviewer-Id", reviewerId);
        mvc.perform(req).andExpect(status().isOk());
    }

    @Test
    void anEditShowsWhoWhenWhereAndBeforeAfter() throws Exception {
        long pid = normalizedSameSourceTwice(TestProjects.freshGame());
        long rid = reviewer("피드 " + pid);
        Segment r2 = row(pid, 2), r6 = row(pid, 6);
        save(r2.getId(), "정의의 판결", rid);

        // jsonPath의 value(...)는 JSON 정수를 Integer로 읽으므로 Long은 (int)로 맞춘다.
        mvc.perform(get("/api/projects/{id}/activity", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextBefore").doesNotExist())
                .andExpect(jsonPath("$.items[0].kind").value("EDIT"))
                .andExpect(jsonPath("$.items[0].reviewer.id").value((int) rid))
                .andExpect(jsonPath("$.items[0].reviewer.name").value("피드 " + pid))
                .andExpect(jsonPath("$.items[0].createdAt").isString())
                .andExpect(jsonPath("$.items[0].rowCount").value(2))
                .andExpect(jsonPath("$.items[0].sheet").value("Skill"))
                .andExpect(jsonPath("$.items[0].primary.segmentId").value(r2.getId().intValue()))
                .andExpect(jsonPath("$.items[0].primary.sheet").value("Skill"))
                .andExpect(jsonPath("$.items[0].primary.rowNo").value(2))
                .andExpect(jsonPath("$.items[0].primary.ordinal").value(0))
                .andExpect(jsonPath("$.items[0].primary.before").value("정의의 심판"))
                .andExpect(jsonPath("$.items[0].primary.after").value("정의의 판결"))
                .andExpect(jsonPath("$.items[0].detail.segmentId").value(r2.getId().intValue()));

        // 행 목록은 대표 행을 빼고 따라 바뀐 행만. 순번은 전체 보기 정렬에서의 위치.
        long aid = repo.findByProjectIdOrderByIdDesc(pid).get(0).getId();
        // Segment에 equals가 없어 indexOf(r6)는 -1 — id로 위치를 찾는다.
        int ordinal6 = rows(pid).stream().map(Segment::getId).toList().indexOf(r6.getId());
        mvc.perform(get("/api/activity/{id}/rows", aid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.rows[0].segmentId").value(r6.getId().intValue()))
                .andExpect(jsonPath("$.rows[0].rowNo").value(6))
                .andExpect(jsonPath("$.rows[0].ordinal").value(ordinal6))
                .andExpect(jsonPath("$.rows[0].before").value("정의의 심판"))
                .andExpect(jsonPath("$.rows[0].after").value("정의의 판결"));
    }

    /** Review Focus 3 */
    @Test
    void anonymousEditShowsNoReviewer() throws Exception {
        long pid = normalizedSameSourceTwice(TestProjects.freshGame());
        save(row(pid, 2).getId(), "정의의 판결", null);
        mvc.perform(get("/api/projects/{id}/activity", pid))
                .andExpect(jsonPath("$.items[0].reviewer").value(Matchers.nullValue()));
    }

    /** Review Focus 2: 정규화가 EXISTING 0판을 넣지 않은 행(기존 한국어가 비어 있던 행)은 이전 판이 없다. */
    @Test
    void theFirstRevisionHasNoBefore() throws Exception {
        long pid = normalizedSameSourceTwice(TestProjects.freshGame());
        // 5행(技能伤害提升至@420%@)은 기존 한국어가 빈 칸이라 리비전이 없다.
        Segment empty = row(pid, 5);
        assertThat(revisions.current(empty.getId())).isEmpty();
        save(empty.getId(), "스킬 피해 @420%@ 증가", null);
        mvc.perform(get("/api/projects/{id}/activity", pid))
                .andExpect(jsonPath("$.items[0].primary.before").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].primary.after").value("스킬 피해 @420%@ 증가"));
    }

    /** Review Focus 4 */
    @Test
    void theCursorExcludesItself() throws Exception {
        long pid = normalizedSameSourceTwice(TestProjects.freshGame());
        Segment r2 = row(pid, 2);
        save(r2.getId(), "첫째", null);
        save(r2.getId(), "둘째", null);
        save(r2.getId(), "셋째", null);
        List<Activity> all = repo.findByProjectIdOrderByIdDesc(pid);
        long newest = all.get(0).getId(), middle = all.get(1).getId(), oldest = all.get(2).getId();

        mvc.perform(get("/api/projects/{id}/activity", pid).param("size", "2"))
                .andExpect(jsonPath("$.items[*].id").value(Matchers.contains((int) newest, (int) middle)))
                .andExpect(jsonPath("$.nextBefore").value((int) middle));
        mvc.perform(get("/api/projects/{id}/activity", pid).param("size", "2").param("before", String.valueOf(middle)))
                .andExpect(jsonPath("$.items[*].id").value(Matchers.contains((int) oldest)))
                .andExpect(jsonPath("$.nextBefore").doesNotExist());
    }

    @Test
    void gameLevelGlossaryEditsAppearInEveryProjectOfTheGame() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalizedSameSourceTwice(game);
        long b = normalizedSameSourceTwice(game);
        Long gid = projects.findById(a).orElseThrow().getGame().getId();
        mvc.perform(post("/api/games/{g}/glossary", gid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"KO\",\"matchMode\":\"WHOLE\",\"find\":\"없는말\",\"ko\":\"없는말2\"}")).andExpect(status().isOk());

        for (long pid : new long[]{a, b})
            mvc.perform(get("/api/projects/{id}/activity", pid))
                    .andExpect(jsonPath("$.items[0].kind").value("GLOSSARY_EDIT"))
                    .andExpect(jsonPath("$.items[0].rowCount").value(0))
                    .andExpect(jsonPath("$.items[0].sheet").value(Matchers.nullValue()))
                    .andExpect(jsonPath("$.items[0].primary").value(Matchers.nullValue()))
                    .andExpect(jsonPath("$.items[0].detail.action").value("ADD"))
                    .andExpect(jsonPath("$.items[0].detail.find").value("없는말"));
    }

    /** 같은 원문 전파는 게임의 다른 파일 행도 바꾼다 — 행 목록의 각 행이 어느 파일인지 알려 줘야 점프가 엉뚱한 행으로 안 간다. */
    @Test
    void propagatedRowsInAnotherProjectCarryTheirProjectId() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalizedSameSourceTwice(game);
        long b = normalizedSameSourceTwice(game);
        save(row(a, 2).getId(), "정의의 판결", null);

        long aid = repo.findByProjectIdOrderByIdDesc(a).get(0).getId();
        mvc.perform(get("/api/activity/{id}/rows", aid))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.rows[?(@.projectId == " + a + ")].rowNo").value(Matchers.contains(6)))
                .andExpect(jsonPath("$.rows[?(@.projectId == " + b + ")].rowNo").value(Matchers.containsInAnyOrder(2, 6)))
                // 리뷰 finding 5: 파일이 섞이면 (시트, 행)이 같은 행이 생기므로 파일 → 시트 → 행 순으로 정해진 순서여야 쪽이 겹치지 않는다.
                .andExpect(jsonPath("$.rows[*].projectId").value(Matchers.contains((int) a, (int) b, (int) b)))
                .andExpect(jsonPath("$.rows[*].rowNo").value(Matchers.contains(6, 2, 6)));
        mvc.perform(get("/api/projects/{id}/activity", a))
                .andExpect(jsonPath("$.items[0].primary.projectId").value((int) a));
    }

    @Test
    void unknownProjectAndActivityAre404() throws Exception {
        mvc.perform(get("/api/projects/{id}/activity", 999_999)).andExpect(status().isNotFound());
        mvc.perform(get("/api/activity/{id}/rows", 999_999)).andExpect(status().isNotFound());
    }
}
