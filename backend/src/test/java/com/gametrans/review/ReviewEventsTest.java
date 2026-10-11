package com.gametrans.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.job.JobEventBus;
import com.gametrans.llm.LlmClient;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class ReviewEventsTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;
    @SpyBean JobEventBus bus;

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
        fake.termsToReturn = List.of(new LlmClient.TermExtraction("烈焰之剑", "ITEM"));
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
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.parse(body).read("$.id")).longValue();
    }

    /** rows[2]는 용어가 아닌 일반 행(ItemText!4). */
    long plainSegment(long pid) throws Exception {
        String list = mvc.perform(get("/api/projects/{id}/segments", pid)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.parse(list).read("$.rows[2].id")).longValue();
    }

    /** 용어를 하나 추출·확정해 isTerm==true인 행을 만든다(샘플 워크북 자체에는 확정된 용어 행이 없다). */
    long confirmedTermSegment(long pid) throws Exception {
        mvc.perform(post("/api/projects/{id}/terms/extract", pid));
        String terms = mvc.perform(get("/api/projects/{id}/terms", pid)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long termId = firstLong(terms, "$.terms[?(@.srcText=='烈焰之剑')].id");
        mvc.perform(patch("/api/terms/{id}", termId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmedKo\":\"화염의 검\",\"confirmed\":true}"));
        String list = mvc.perform(get("/api/projects/{id}/segments", pid)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return firstLong(list, "$.rows[?(@.isTerm==true)].id");
    }

    private static long firstLong(String json, String path) {
        List<?> values = JsonPath.parse(json).read(path, List.class);
        return values.stream().map(o -> ((Number) o).longValue()).findFirst().orElseThrow();
    }

    private <T> List<T> reviewEvents(long pid, Class<T> type) {
        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(bus, atLeastOnce()).publishProjectEvent(eq(pid), eq("review"), data.capture());
        return data.getAllValues().stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    void humanSaveAndDoneCheckPublishTheUpdatedRow() throws Exception {
        long pid = translated(), who = reviewer("지민"), sid = plainSegment(pid);
        clearInvocations(bus);

        mvc.perform(put("/api/segments/{id}/translation", sid).header("X-Reviewer-Id", who)
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"서리\"}"));
        List<ReviewEvents.SegmentUpdated> saved = reviewEvents(pid, ReviewEvents.SegmentUpdated.class);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).type()).isEqualTo("segment-updated");
        assertThat(saved.get(0).by()).isEqualTo(who);
        assertThat(saved.get(0).row().id()).isEqualTo(sid);
        assertThat(saved.get(0).row().currentText()).isEqualTo("서리");
        assertThat(saved.get(0).row().lastEditor().id()).isEqualTo(who);

        clearInvocations(bus);
        mvc.perform(put("/api/segments/{id}/done", sid).contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}"));
        List<ReviewEvents.SegmentUpdated> done = reviewEvents(pid, ReviewEvents.SegmentUpdated.class);
        assertThat(done).hasSize(1);
        assertThat(done.get(0).by()).isNull();
        assertThat(done.get(0).row().done()).isTrue();
    }

    @Test
    void assignPublishesEveryAffectedSegmentIdAndTheAssignee() throws Exception {
        long pid = translated(), who = reviewer("서연"), sid = plainSegment(pid);
        clearInvocations(bus);
        mvc.perform(post("/api/projects/{id}/assign", pid).header("X-Reviewer-Id", who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sheet\":\"ItemText\",\"fromRow\":3,\"toRow\":4,\"assigneeId\":" + who + "}"));
        List<ReviewEvents.Assigned> events = reviewEvents(pid, ReviewEvents.Assigned.class);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("assigned");
        assertThat(events.get(0).by()).isEqualTo(who);
        assertThat(events.get(0).segmentIds()).hasSize(2).contains(sid);
        assertThat(events.get(0).assignee().id()).isEqualTo(who);

        clearInvocations(bus);
        mvc.perform(post("/api/projects/{id}/assign", pid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"segmentIds\":[" + sid + "],\"assigneeId\":null}"));
        List<ReviewEvents.Assigned> cleared = reviewEvents(pid, ReviewEvents.Assigned.class);
        assertThat(cleared.get(0).assignee()).isNull();
        assertThat(cleared.get(0).segmentIds()).containsExactly(sid);
    }

    @Test
    void editingATermRowAsksClientsToRefreshBecauseManyRowsChange() throws Exception {
        long pid = translated();
        long termSegmentId = confirmedTermSegment(pid);
        clearInvocations(bus);
        mvc.perform(put("/api/segments/{id}/translation", termSegmentId).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"불꽃검\"}"));
        List<ReviewEvents.Refresh> refreshes = reviewEvents(pid, ReviewEvents.Refresh.class);
        assertThat(refreshes).hasSize(1);
        assertThat(refreshes.get(0).by()).isNull();   // 헤더 없이 고친 익명 수정
    }

    /** 이름이 바뀌면 그 사람이 담당으로 찍혀 있는 프로젝트의 열린 화면들은 목록을 다시 읽어야 새 이름을 본다. */
    @Test
    void renamingAReviewerAsksTheProjectsTheyAppearInToRefresh() throws Exception {
        long pid = translated(), who = reviewer("지민"), sid = plainSegment(pid);
        mvc.perform(post("/api/projects/{id}/assign", pid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"segmentIds\":[" + sid + "],\"assigneeId\":" + who + "}")).andExpect(status().isOk());
        clearInvocations(bus);

        mvc.perform(patch("/api/reviewers/{id}", who).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"지민-고침\"}"))
                .andExpect(status().isOk());
        assertThat(reviewEvents(pid, ReviewEvents.Refresh.class)).hasSize(1);
    }

    /** 삭제는 담당·기록이 통째로 옮겨 가므로 더더욱 다시 읽어야 한다. 옮길 프로젝트는 FK가 움직이기 전에 모아야 한다. */
    @Test
    void deletingAReviewerAsksTheProjectsTheyAppearInToRefresh() throws Exception {
        long pid = translated(), who = reviewer("서연"), heir = reviewer("민준"), sid = plainSegment(pid);
        mvc.perform(put("/api/segments/{id}/translation", sid).header("X-Reviewer-Id", who)
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"서리\"}")).andExpect(status().isOk());
        clearInvocations(bus);

        mvc.perform(delete("/api/reviewers/{id}", who).param("transferTo", String.valueOf(heir)))
                .andExpect(status().isNoContent());
        assertThat(reviewEvents(pid, ReviewEvents.Refresh.class)).hasSize(1);
    }

    /** 아무 행에도 안 붙어 있는 검토자를 정리하는 건 어느 화면과도 상관없다 — 괜히 전원 재조회를 시키지 않는다. */
    @Test
    void renamingAReviewerNobodyIsTaggedWithPublishesNothing() throws Exception {
        long who = reviewer("한번도-안쓴");
        clearInvocations(bus);
        mvc.perform(patch("/api/reviewers/{id}", who).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"그대로-미사용\"}"))
                .andExpect(status().isOk());
        verify(bus, never()).publishProjectEvent(any(), eq(ReviewEvents.EVENT), any());
    }
}
