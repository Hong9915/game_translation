package com.gametrans.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.job.JobEventBus;
import com.gametrans.review.ReviewEvents;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Claude 제안 저장·목록·상세. 2·3행 원문이 같고 Fix "정의의 심판", 4행 "공격". */
class ConnectorProposalTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @SpyBean JobEventBus bus;

    long pid, gameId, r2, r3, r4, me, other;

    @BeforeEach
    void setUp() throws Exception {
        pid = TestProjects.uploadFinalSameSourceTwice(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.FINAL_SKILL_NORMALIZE)).andExpect(status().isOk());
        gameId = jdbc.queryForObject("select game_id from project where id = ?", Long.class, pid);
        r2 = row(2);
        r3 = row(3);
        r4 = row(4);
        me = ConnectorSaveTest.newReviewer(mvc);
        other = ConnectorSaveTest.newReviewer(mvc);
    }

    long row(int rowNo) {
        return jdbc.queryForObject("select id from segment where project_id = ? and row_no = ? and skipped = false order by id limit 1",
                Long.class, pid, rowNo);
    }

    static String item(long sid, String text, String base, String reason) {
        return "{\"segmentId\":" + sid + ",\"text\":\"" + text + "\",\"base\":" + (base == null ? "null" : "\"" + base + "\"")
                + ",\"reason\":" + (reason == null ? "null" : "\"" + reason + "\"") + "}";
    }

    static String body(String title, String... items) {
        return "{\"title\":" + (title == null ? "null" : "\"" + title + "\"") + ",\"items\":[" + String.join(",", items) + "]}";
    }

    ResultActions propose(Long by, String json) throws Exception {
        var req = post("/api/connector/proposals").contentType(MediaType.APPLICATION_JSON).content(json);
        if (by != null) req.header("X-Reviewer-Id", by);
        return mvc.perform(req);
    }

    DocumentContext json(ResultActions a) throws Exception {
        return JsonPath.parse(a.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    long proposeOk(String title, String... items) throws Exception {
        return json(propose(me, body(title, items)).andExpect(status().isOk())).read("$.proposalId", Long.class);
    }

    ResultActions list(long reviewerId) throws Exception {
        return mvc.perform(get("/api/connector/proposals").param("reviewerId", String.valueOf(reviewerId)).param("gameId", String.valueOf(gameId)));
    }

    ResultActions detail(long id) throws Exception { return mvc.perform(get("/api/connector/proposals/{id}", id)); }

    @Test
    void autoBaseFillsCurrentFix() throws Exception {
        String it = "{\"segmentId\":" + r2 + ",\"text\":\"정의 심판\",\"base\":null,\"autoBase\":true,\"reason\":null}";
        long id = proposeOk("자동", it);
        detail(id).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].base").value("정의의 심판"));
    }

    @Test
    void proposeStoresPendingAndListsNewestFirst() throws Exception {
        proposeOk("첫째", item(r2, "정의 심판", "정의의 심판", "r"));
        proposeOk("둘째", item(r2, "정의 심판", "정의의 심판", "r"), item(r4, "공격함", "공격", null));
        list(me).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("둘째")).andExpect(jsonPath("$[0].rowCount").value(2))
                .andExpect(jsonPath("$[1].title").value("첫째")).andExpect(jsonPath("$[1].rowCount").value(1));
        list(other).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void proposeRequiresReviewer() throws Exception {
        String b = body("t", item(r2, "정의 심판", "정의의 심판", null));
        propose(null, b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("검수자를 찾을 수 없습니다")));
        propose(ConnectorSaveTest.deletedReviewer(mvc), b).andExpect(status().isBadRequest());
    }

    @Test
    void proposeValidatesItems() throws Exception {
        String range = "한 번에 1~500행을 제안할 수 있습니다.";
        propose(me, body("t")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(range));
        String[] many = IntStream.rangeClosed(1, 501).mapToObj(i -> item(900_000_000L + i, "가", null, null)).toArray(String[]::new);
        propose(me, body("t", many)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(range));
        propose(me, body("t", item(r2, "가", null, null), item(r2, "나", null, null))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("같은 행이 두 번 들어 있습니다: " + r2));
        propose(me, body("t", item(r2, "  ", null, null))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("빈 글은 제안할 수 없습니다: " + r2));
        propose(me, body("t", item(900_000_001L, "가", null, null))).andExpect(status().isNotFound());
        long pid2 = TestProjects.uploadExistingKo(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid2).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE))
                .andExpect(status().isOk());
        long foreign = jdbc.queryForObject("select id from segment where project_id = ? and skipped = false order by id limit 1", Long.class, pid2);
        propose(me, body("t", item(r2, "가", null, null), item(foreign, "나", null, null))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("한 게임의 행만 함께 제안할 수 있습니다."));
    }

    @Test
    void proposeReportsLockedRows() throws Exception {
        jdbc.update("update segment set confirmed = true where id = ?", r4);
        DocumentContext r = json(propose(me, body("t", item(r2, "정의 심판", "정의의 심판", null), item(r4, "공격함", "공격", null))).andExpect(status().isOk()));
        assertThat(r.read("$.lockedRows.length()", Integer.class)).isEqualTo(1);
        assertThat(r.read("$.lockedRows[0].id", Long.class)).isEqualTo(r4);
        assertThat(r.read("$.lockedRows[0].reason", String.class)).isEqualTo("확정");
        detail(r.read("$.proposalId", Long.class)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[1].lockedReason").value("확정")).andExpect(jsonPath("$.rows[0].lockedReason").doesNotExist());
    }

    @Test
    void detailShowsRowInfo() throws Exception {
        long id = proposeOk("제목", item(r3, "정의 심판", "정의의 심판", "줄임"), item(r4, "공격함", "공격", "쉽게"));
        detail(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.title").value("제목"))
                .andExpect(jsonPath("$.reviewerId").value(me)).andExpect(jsonPath("$.gameId").value(gameId))
                .andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(jsonPath("$.rows[0].segmentId").value(r3))
                .andExpect(jsonPath("$.rows[0].src").isNotEmpty())
                .andExpect(jsonPath("$.rows[0].fix").value("정의의 심판"))
                .andExpect(jsonPath("$.rows[0].fileName").value("Skill_final2.xlsx"))
                .andExpect(jsonPath("$.rows[0].sheet").isNotEmpty())
                .andExpect(jsonPath("$.rows[0].rowNo").value(3))
                .andExpect(jsonPath("$.rows[0].finalReview").value(true))
                .andExpect(jsonPath("$.rows[0].base").value("정의의 심판"))
                .andExpect(jsonPath("$.rows[0].text").value("정의 심판"))
                .andExpect(jsonPath("$.rows[0].reason").value("줄임"))
                .andExpect(jsonPath("$.rows[1].segmentId").value(r4))
                .andExpect(jsonPath("$.rows[1].fix").value("공격"));
    }

    @Test
    void detailLocksStaleRow() throws Exception {
        long id = proposeOk("t", item(r2, "정의 심판", "정의의 심판", null), item(r4, "공격함", "공격", null));
        mvc.perform(post("/api/segments/save-batch").contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"segmentId\":" + r2 + ",\"text\":\"완전히 다름\",\"base\":\"정의의 심판\"}]}")).andExpect(status().isOk());
        detail(id).andExpect(jsonPath("$.rows[0].lockedReason").value("그 사이 바뀜")).andExpect(jsonPath("$.rows[1].lockedReason").doesNotExist());
        // 지금 Fix가 제안 글과 이미 같으면 잠그지 않는다
        mvc.perform(post("/api/segments/save-batch").contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"segmentId\":" + r2 + ",\"text\":\"정의 심판\",\"base\":\"완전히 다름\"}]}")).andExpect(status().isOk());
        detail(id).andExpect(jsonPath("$.rows[0].lockedReason").doesNotExist());
    }

    @Test
    void detailLocksMissingRow() throws Exception {
        long id = proposeOk("t", item(r4, "공격함", "공격", "이유"), item(r2, "정의 심판", "정의의 심판", null));
        jdbc.update("delete from segment where id = ?", r4);
        detail(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].segmentId").value(r4)).andExpect(jsonPath("$.rows[0].lockedReason").value("행이 없어짐"))
                .andExpect(jsonPath("$.rows[0].src").doesNotExist()).andExpect(jsonPath("$.rows[0].text").value("공격함"))
                .andExpect(jsonPath("$.rows[0].base").value("공격")).andExpect(jsonPath("$.rows[0].reason").value("이유"))
                .andExpect(jsonPath("$.rows[1].lockedReason").doesNotExist());
    }

    private void touchFile(long reviewer, long project) throws Exception {
        mvc.perform(post("/api/projects/{id}/presence", project).header("X-Reviewer-Id", reviewer).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private void touchGame(long reviewer, long game) throws Exception {
        mvc.perform(post("/api/games/{g}/review/presence", game).header("X-Reviewer-Id", reviewer).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private boolean presentNow() throws Exception {
        return json(propose(me, body("t", item(r2, "정의 심판", "정의의 심판", null))).andExpect(status().isOk())).read("$.presentNow", Boolean.class);
    }

    @Test
    void presentNowFollowsPresence() throws Exception {
        assertThat(presentNow()).isFalse();
        touchGame(other, gameId);
        assertThat(presentNow()).isFalse();
        // 다른 게임 화면에만 있으면 false
        long otherGame = jdbc.queryForObject("select game_id from project where id = ?", Long.class, TestProjects.upload(mvc, TestProjects.freshGame()));
        touchGame(me, otherGame);
        assertThat(presentNow()).isFalse();
        touchFile(me, pid);
        assertThat(presentNow()).isTrue();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/projects/{id}/presence", pid).header("X-Reviewer-Id", me))
                .andExpect(status().is2xxSuccessful());
        assertThat(presentNow()).isFalse();
        touchGame(me, gameId);
        assertThat(presentNow()).isTrue();
    }

    @Test
    void proposeSendsEventToGameAndFileStreams() throws Exception {
        proposeOk("t", item(r2, "정의 심판", "정의의 심판", null));
        verify(bus).publishGameEvent(eq(gameId), eq(ReviewEvents.PROPOSAL_EVENT), argThat(p -> p instanceof ReviewEvents.ProposalEvent e
                && e.type().equals("connector-proposal") && e.reviewerId().equals(me)));
        verify(bus).publishProjectEvent(eq(pid), eq(ReviewEvents.PROPOSAL_EVENT), argThat(p -> p instanceof ReviewEvents.ProposalEvent e
                && e.type().equals("connector-proposal") && e.reviewerId().equals(me)));
        // 옛 프런트는 "review"만 구독한다 — 제안 payload가 그 이름으로 가면 안 된다
        verify(bus, org.mockito.Mockito.never()).publishGameEvent(anyLong(), eq(ReviewEvents.EVENT), argThat(p -> p instanceof ReviewEvents.ProposalEvent));
        verify(bus, org.mockito.Mockito.never()).publishProjectEvent(anyLong(), eq(ReviewEvents.EVENT), argThat(p -> p instanceof ReviewEvents.ProposalEvent));
    }

    @Test
    void proposeTrimsText() throws Exception {
        long id = proposeOk("t", item(r2, " 정의의 심판 ", "정의의 심판", null));
        detail(id).andExpect(jsonPath("$.rows[0].text").value("정의의 심판")).andExpect(jsonPath("$.rows[0].lockedReason").doesNotExist());
    }

    @Test
    void expiredProposalLeavesListAndReadsExpired() throws Exception {
        long id = proposeOk("t", item(r2, "정의 심판", "정의의 심판", null));
        jdbc.update("update connector_proposal set created_at = now() - interval '25 hours' where id = ?", id);
        list(me).andExpect(jsonPath("$.length()").value(0));
        detail(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"));
    }
}
