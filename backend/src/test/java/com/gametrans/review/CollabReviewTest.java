package com.gametrans.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.llm.LlmClient;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.review.ReviewDtos.AssignRequest;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class CollabReviewTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;
    @Autowired SegmentRepository segmentRepo;
    @Autowired AssignmentService assignments;
    /** 한 요청이 세그먼트를 읽은 뒤 저장하기 전에 다른 요청이 끼어드는 순간을 만들기 위해서만 감싼다. */
    @SpyBean TermMissService termMiss;

    @BeforeEach
    void setUp() {
        FakeLlmClient fake = (FakeLlmClient) llm;
        fake.translateCalls.clear();
        fake.translations.clear();
        fake.translations.put("烈焰之剑", "화염의 검");
        fake.translations.put("霜语者", "서리속삭임");
        fake.translations.put("一把附有火焰之力的长剑，攻击时有概率造成灼烧。", "화염의 힘이 깃든 장검. 공격 시 확률적으로 화상을 입힙니다.");
        fake.translations.put("使用后恢复{0}点生命值，冷却{1}秒。", "사용 시 HP {0} 회복. 재사용 대기 {1}초.");
        fake.translations.put("<color=#FFD700>传说</color> 品质装备", "<color=#FFD700>전설</color> 등급 장비");
    }

    long translated() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"));
        return pid;
    }

    long reviewer(String prefix) throws Exception {
        String name = prefix + "-" + UUID.randomUUID().toString().substring(0, 6);
        String body = mvc.perform(post("/api/reviewers").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.parse(body).read("$.id")).longValue();
    }

    long segmentId(long pid, int index) throws Exception {
        String list = mvc.perform(get("/api/projects/{id}/segments", pid)).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.parse(list).read("$.rows[" + index + "].id")).longValue();
    }

    @Test
    void humanEditRecordsWhoEditedAndExposesRowPosition() throws Exception {
        long pid = translated();
        long rid = reviewer("지민");
        long sid = segmentId(pid, 2);

        mvc.perform(put("/api/segments/{id}/translation", sid).header("X-Reviewer-Id", rid)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"서리\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.lastEditor.id").value(rid))
                .andExpect(jsonPath("$.row.lastEditedAt").isNotEmpty())
                .andExpect(jsonPath("$.row.history[1].author").value("HUMAN"))
                .andExpect(jsonPath("$.row.history[1].reviewerName").value(org.hamcrest.Matchers.startsWith("지민-")))
                .andExpect(jsonPath("$.row.history[0].reviewerName").doesNotExist())
                .andExpect(jsonPath("$.row.sheetName").value("ItemText"))
                .andExpect(jsonPath("$.row.rowNo").value(4))
                .andExpect(jsonPath("$.row.done").value(false))
                .andExpect(jsonPath("$.row.assignee").doesNotExist());
    }

    @Test
    void editWithoutHeaderStaysAnonymousAndUnknownReviewerIsRejected() throws Exception {
        long pid = translated();
        long sid = segmentId(pid, 2);
        mvc.perform(put("/api/segments/{id}/translation", sid).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"서리\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.currentText").value("서리"))
                .andExpect(jsonPath("$.row.lastEditor").doesNotExist());
        mvc.perform(put("/api/segments/{id}/translation", sid).header("X-Reviewer-Id", 999999999L)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"서리2\"}"))
                .andExpect(status().isBadRequest());
        // 브라우저에 숫자가 아닌 값이 남아 있어도 서버 오류가 아니라 잘못된 요청이다
        mvc.perform(put("/api/segments/{id}/translation", sid).header("X-Reviewer-Id", "어쩌구")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"서리3\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("X-Reviewer-Id 값이 올바르지 않습니다."));
    }

    @Test
    void editingTextKeepsTheDoneCheckAndTheAssignee() throws Exception {
        long pid = translated();
        long jimin = reviewer("지민");
        long sid = segmentId(pid, 2);

        mvc.perform(post("/api/projects/{id}/assign", pid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"segmentIds\":[" + sid + "],\"assigneeId\":" + jimin + "}")).andExpect(status().isOk());
        mvc.perform(put("/api/segments/{id}/done", sid).header("X-Reviewer-Id", jimin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}")).andExpect(status().isOk());

        mvc.perform(put("/api/segments/{id}/translation", sid).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"서리\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.done").value(true))
                .andExpect(jsonPath("$.row.assignee.id").value(jimin));
    }

    /**
     * 번역 저장은 자기가 바꾸는 칸(용어 누락)만 써야 한다. 읽고 저장하는 사이에 다른 요청이 완료·담당을 바꾸면,
     * 예전 값이 든 복사본을 통째로 저장할 때 그 변경이 조용히 되돌아간다(글을 쓰다 완료를 누르는 가장 흔한 동작).
     */
    @Test
    void savingTextDoesNotRevertDoneOrAssigneeChangedMeanwhile() throws Exception {
        long pid = translated();
        long jimin = reviewer("지민");
        long sid = segmentId(pid, 2);

        // 저장 직전(단어장 조회 시점)에 다른 요청이 담당과 완료를 기록한다
        AtomicBoolean once = new AtomicBoolean();
        org.mockito.Mockito.doAnswer(inv -> {
            Object glossary = inv.callRealMethod();
            if (once.compareAndSet(false, true)) {
                assignments.assign(pid, new AssignRequest(null, List.of(sid), null, null, jimin), null);
                assignments.done(sid, true, jimin, null);
            }
            return glossary;
        }).when(termMiss).glossaryFor(pid);

        mvc.perform(put("/api/segments/{id}/translation", sid).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"서리 속삭임\"}"))
                .andExpect(status().isOk());

        Segment after = segmentRepo.findById(sid).orElseThrow();
        assertThat(after.isDone()).isTrue();
        assertThat(after.getAssigneeId()).isEqualTo(jimin);
        assertThat(after.getDoneBy()).isEqualTo(jimin);
    }

    @Test
    void assignsByIdsAndByRowRangeThenFiltersAndUnassigns() throws Exception {
        long pid = translated();
        long jimin = reviewer("지민"), seoyeon = reviewer("서연");
        long first = segmentId(pid, 0);

        mvc.perform(post("/api/projects/{id}/assign", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segmentIds\":[" + first + "],\"assigneeId\":" + jimin + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(1));
        mvc.perform(post("/api/projects/{id}/assign", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sheet\":\"ItemText\",\"fromRow\":3,\"toRow\":4,\"assigneeId\":" + seoyeon + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(2));

        mvc.perform(get("/api/projects/{id}/segments", pid).param("assignee", String.valueOf(seoyeon)))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.rows[0].assignee.id").value(seoyeon))
                .andExpect(jsonPath("$.stats.total").value(5));   // 통계는 필터와 무관하게 전체 기준
        mvc.perform(get("/api/projects/{id}/segments", pid).param("assignee", "none"))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/projects/{id}/segments", pid).param("sheet", "없는시트"))
                .andExpect(jsonPath("$.total").value(0));

        mvc.perform(post("/api/projects/{id}/assign", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segmentIds\":[" + first + "],\"assigneeId\":null}"))
                .andExpect(jsonPath("$.updated").value(1));
        mvc.perform(get("/api/projects/{id}/segments", pid).param("assignee", "none"))
                .andExpect(jsonPath("$.total").value(3));

        mvc.perform(post("/api/projects/{id}/assign", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segmentIds\":[" + first + "],\"assigneeId\":999999999}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/projects/{id}/assign", pid).contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":" + jimin + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/projects/{id}/assign", pid).header("X-Reviewer-Id", 999999999L).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segmentIds\":[" + first + "],\"assigneeId\":" + jimin + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void doneCheckRecordsWhoAndCanBeClearedByAnyone() throws Exception {
        long pid = translated();
        long jimin = reviewer("지민");
        long sid = segmentId(pid, 1);

        mvc.perform(put("/api/segments/{id}/done", sid).header("X-Reviewer-Id", jimin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.done").value(true));
        mvc.perform(get("/api/projects/{id}/segments", pid).param("done", "true")).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/projects/{id}/segments", pid).param("done", "false")).andExpect(jsonPath("$.total").value(4));
        Segment checked = segmentRepo.findById(sid).orElseThrow();
        assertThat(checked.getDoneBy()).isEqualTo(jimin);
        assertThat(checked.getDoneAt()).isNotNull();

        mvc.perform(put("/api/segments/{id}/done", sid).contentType(MediaType.APPLICATION_JSON).content("{\"done\":false}"))
                .andExpect(jsonPath("$.done").value(false));
        Segment cleared = segmentRepo.findById(sid).orElseThrow();
        assertThat(cleared.getDoneBy()).isNull();
        assertThat(cleared.getDoneAt()).isNull();
    }

    @Test
    void assigningOnePairAssignsAllPairsOfTheSameRow() throws Exception {
        long pid = TestProjects.uploadTwoPairs(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.TWO_PAIRS_NORMALIZE))
                .andExpect(status().isOk());
        long minjun = reviewer("민준");
        long namePair = segmentId(pid, 0);   // Skill!2!B (2행, pairIndex 0) — 같은 행의 설명 열(E, pairIndex 1)은 넣지 않는다

        mvc.perform(post("/api/projects/{id}/assign", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segmentIds\":[" + namePair + "],\"assigneeId\":" + minjun + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(2));

        String list = mvc.perform(get("/api/projects/{id}/segments", pid).param("assignee", String.valueOf(minjun)))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        List<Integer> rowNos = JsonPath.parse(list).read("$.rows[*].rowNo");
        List<Integer> pairIndexes = JsonPath.parse(list).read("$.rows[*].pairIndex");
        assertThat(rowNos).containsExactly(2, 2);   // 다른 행(4행)에서는 아무것도 배정되지 않았다
        assertThat(pairIndexes).containsExactlyInAnyOrder(0, 1);   // 이름·설명 열 쌍 둘 다 배정됐다
    }

    @Test
    void progressCountsAssignedAndDonePerReviewer() throws Exception {
        long pid = translated();
        long jimin = reviewer("지민");
        mvc.perform(post("/api/projects/{id}/assign", pid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sheet\":\"ItemText\",\"fromRow\":2,\"toRow\":4,\"assigneeId\":" + jimin + "}"));
        mvc.perform(put("/api/segments/{id}/done", segmentId(pid, 0)).contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}"));

        mvc.perform(get("/api/projects/{id}/review-progress", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.done").value(1))
                .andExpect(jsonPath("$.unassigned").value(2))
                .andExpect(jsonPath("$.reviewers.length()").value(1))
                .andExpect(jsonPath("$.reviewers[0].reviewer.id").value(jimin))
                .andExpect(jsonPath("$.reviewers[0].assigned").value(3))
                .andExpect(jsonPath("$.reviewers[0].done").value(1))
                .andExpect(jsonPath("$.sheets[0]").value("ItemText"));

        // 시트를 주면 그 시트만 센다 (이 파일은 ItemText 한 장이라 전체와 같다)
        mvc.perform(get("/api/projects/{id}/review-progress", pid).param("sheet", "ItemText"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.reviewers[0].assigned").value(3))
                .andExpect(jsonPath("$.unassigned").value(2));
        mvc.perform(get("/api/projects/{id}/review-progress", pid).param("sheet", "없는시트"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.reviewers.length()").value(0));
    }
}
