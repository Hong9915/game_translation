package com.gametrans.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Claude 제안 저장(apply)·넣지 않음(dismiss). 2·3행 원문이 같고 Fix "정의의 심판", 4행 "공격". */
class ConnectorProposalApplyTest extends IntegrationTest {
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

    DocumentContext json(ResultActions a) throws Exception {
        return JsonPath.parse(a.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 제안: 2행 "심판 A"(base 정의의 심판), 4행 "공격 B"(base 공격). */
    long propose() throws Exception {
        String b = ConnectorProposalTest.body("제안", ConnectorProposalTest.item(r2, "심판 A", "정의의 심판", "r"),
                ConnectorProposalTest.item(r4, "공격 B", "공격", null));
        return json(mvc.perform(post("/api/connector/proposals").contentType(MediaType.APPLICATION_JSON).header("X-Reviewer-Id", me).content(b))
                .andExpect(status().isOk())).read("$.proposalId", Long.class);
    }

    ResultActions apply(Long by, long id, String ids) throws Exception {
        var req = post("/api/connector/proposals/{id}/apply", id).contentType(MediaType.APPLICATION_JSON).content("{\"segmentIds\":" + ids + "}");
        if (by != null) req.header("X-Reviewer-Id", by);
        return mvc.perform(req);
    }

    ResultActions dismiss(Long by, long id) throws Exception {
        var req = post("/api/connector/proposals/{id}/dismiss", id);
        if (by != null) req.header("X-Reviewer-Id", by);
        return mvc.perform(req);
    }

    ResultActions detail(long id) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/connector/proposals/{id}", id));
    }

    String fix(long sid) { return jdbc.queryForObject("select latest_ko from segment where id = ?", String.class, sid); }

    boolean mark(long sid) { return jdbc.queryForObject("select final_mark from segment where id = ?", Boolean.class, sid); }

    long connectorActivities() { return jdbc.queryForObject("select count(*) from activity where kind = 'CONNECTOR' and reviewer_id = ?", Long.class, me); }

    @Test
    void applySavesOnlyCheckedRows() throws Exception {
        long id = propose();
        DocumentContext r = json(apply(me, id, "[" + r2 + "]").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED")).andExpect(jsonPath("$.decidedAt").isNotEmpty()));
        assertThat(r.read("$.result.saved", Integer.class)).isEqualTo(1);
        assertThat(r.read("$.result.excluded", java.util.List.class)).containsExactly((int) r4);
        assertThat(r.read("$.result.propagated", Integer.class)).isEqualTo(1);
        assertThat(r.read("$.result.finalMarked", Integer.class)).isGreaterThanOrEqualTo(1);
        assertThat(fix(r2)).isEqualTo("심판 A");
        assertThat(fix(r3)).isEqualTo("심판 A");
        assertThat(fix(r4)).isEqualTo("공격");
        assertThat(mark(r2)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from activity where kind = 'CONNECTOR' and reviewer_id = ?", Long.class, me)).isEqualTo(1L);
    }

    @Test
    void applyRecordsSkippedRows() throws Exception {
        long id = propose();
        jdbc.update("update segment set confirmed = true where id = ?", r4);
        mvc.perform(post("/api/segments/save-batch").contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"segmentId\":" + r2 + ",\"text\":\"웹에서 바꿈\",\"base\":\"정의의 심판\"}]}")).andExpect(status().isOk());
        apply(me, id, "[" + r2 + "," + r4 + "]").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.result.saved").value(0))
                .andExpect(jsonPath("$.result.skippedRows[?(@.id == " + r2 + " && @.reason == '그 사이 바뀜')]").exists())
                .andExpect(jsonPath("$.result.skippedRows[?(@.id == " + r4 + " && @.reason == '확정')]").exists());
    }

    @Test
    void applyReportsLockedUncheckedRowsAsSkipped() throws Exception {
        String b = ConnectorProposalTest.body("셋", ConnectorProposalTest.item(r2, "심판 A", "정의의 심판", null),
                ConnectorProposalTest.item(r3, "심판 B", "정의의 심판", null), ConnectorProposalTest.item(r4, "공격 C", "공격", null));
        long id = json(mvc.perform(post("/api/connector/proposals").contentType(MediaType.APPLICATION_JSON).header("X-Reviewer-Id", me).content(b))
                .andExpect(status().isOk())).read("$.proposalId", Long.class);
        mvc.perform(post("/api/segments/save-batch").contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"segmentId\":" + r2 + ",\"text\":\"웹에서 바꿈\",\"base\":\"정의의 심판\"}]}")).andExpect(status().isOk());
        jdbc.update("update segment set confirmed = true where id = ?", r3);
        apply(me, id, "[" + r4 + "]").andExpect(status().isOk())
                .andExpect(jsonPath("$.result.excluded.length()").value(0))
                .andExpect(jsonPath("$.result.skippedRows[?(@.id == " + r2 + " && @.reason == '그 사이 바뀜')]").exists())
                .andExpect(jsonPath("$.result.skippedRows[?(@.id == " + r3 + " && @.reason == '확정')]").exists());
        // 지워진 체크 안 한 행
        long id2 = propose();
        jdbc.update("delete from segment where id = ?", r4);
        apply(me, id2, "[" + r2 + "]").andExpect(status().isOk())
                .andExpect(jsonPath("$.result.excluded.length()").value(0))
                .andExpect(jsonPath("$.result.skippedRows[?(@.id == " + r4 + " && @.reason == '행이 없어짐')]").exists());
    }

    @Test
    void uncheckedSameSourceRowStaysExcluded() throws Exception {
        String b = ConnectorProposalTest.body("같은 원문", ConnectorProposalTest.item(r2, "심판 A", "정의의 심판", null),
                ConnectorProposalTest.item(r3, "심판 B", "정의의 심판", null));
        long id = json(mvc.perform(post("/api/connector/proposals").contentType(MediaType.APPLICATION_JSON).header("X-Reviewer-Id", me).content(b))
                .andExpect(status().isOk())).read("$.proposalId", Long.class);
        apply(me, id, "[" + r2 + "]").andExpect(status().isOk())
                .andExpect(jsonPath("$.result.excluded[0]").value((int) r3))
                .andExpect(jsonPath("$.result.excluded.length()").value(1))
                .andExpect(jsonPath("$.result.skippedRows[?(@.id == " + r3 + ")]").doesNotExist());
    }

    @Test
    void applySkipsMissingRow() throws Exception {
        long id = propose();
        jdbc.update("delete from segment where id = ?", r4);
        apply(me, id, "[" + r2 + "," + r4 + "]").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.result.saved").value(1))
                .andExpect(jsonPath("$.result.skippedRows[?(@.id == " + r4 + " && @.reason == '행이 없어짐')]").exists());
        // 둘 다 없으면 저장 0이지만 여전히 200·APPLIED
        long id2 = propose2WithoutR4();
        jdbc.update("delete from segment where id = ?", r2);
        jdbc.update("delete from segment where id = ?", r3);
        apply(me, id2, "[" + r2 + "]").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED")).andExpect(jsonPath("$.result.saved").value(0))
                .andExpect(jsonPath("$.result.skippedRows[0].reason").value("행이 없어짐"));
    }

    private long propose2WithoutR4() throws Exception {
        String b = ConnectorProposalTest.body("둘째", ConnectorProposalTest.item(r2, "심판 C", fix(r2), null));
        return json(mvc.perform(post("/api/connector/proposals").contentType(MediaType.APPLICATION_JSON).header("X-Reviewer-Id", me).content(b))
                .andExpect(status().isOk())).read("$.proposalId", Long.class);
    }

    @Test
    void applyIsUndoableByConnector() throws Exception {
        long id = propose();
        DocumentContext r = json(apply(me, id, "[" + r2 + "]").andExpect(status().isOk()));
        String ids = r.read("$.result.activityIds").toString();
        mvc.perform(post("/api/connector/undo").contentType(MediaType.APPLICATION_JSON).header("X-Reviewer-Id", me)
                .content("{\"activityIds\":" + ids + "}")).andExpect(status().isOk());
        assertThat(fix(r2)).isEqualTo("정의의 심판");
        assertThat(fix(r3)).isEqualTo("정의의 심판");
        assertThat(mark(r2)).isFalse();
    }

    @Test
    void applyTwiceIs409() throws Exception {
        long id = propose();
        apply(me, id, "[" + r2 + "]").andExpect(status().isOk());
        apply(me, id, "[" + r2 + "]").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 처리된 제안입니다."))
                .andExpect(jsonPath("$.proposal.status").value("APPLIED"))
                .andExpect(jsonPath("$.proposal.result.saved").value(1));
        assertThat(connectorActivities()).isEqualTo(1L);
        dismiss(me, id).andExpect(status().isConflict()).andExpect(jsonPath("$.proposal.status").value("APPLIED"));
    }

    @Test
    void applyExpiredIs409() throws Exception {
        long id = propose();
        jdbc.update("update connector_proposal set created_at = now() - interval '25 hours' where id = ?", id);
        apply(me, id, "[" + r2 + "]").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("만료된 제안입니다(만든 지 24시간이 지났습니다)."))
                .andExpect(jsonPath("$.proposal.status").value("EXPIRED"));
        assertThat(fix(r2)).isEqualTo("정의의 심판");
    }

    private void assertStillPending(long id) throws Exception {
        detail(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    void applyRejectsOtherReviewerAndBadIds() throws Exception {
        long id = propose();
        apply(other, id, "[" + r2 + "]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이 제안은 다른 검수자에게 온 것입니다."));
        assertStillPending(id);
        apply(null, id, "[" + r2 + "]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("검수자를 찾을 수 없습니다")));
        assertStillPending(id);
        apply(me, id, "[]").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("저장할 행을 하나 이상 체크해 주세요."));
        assertStillPending(id);
        apply(me, id, "[" + r3 + "]").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("제안에 없는 행입니다: " + r3));
        assertStillPending(id);
        assertThat(fix(r2)).isEqualTo("정의의 심판");
    }

    @Test
    void dismissMarksDismissedAndNotifies() throws Exception {
        long id = propose();
        dismiss(other, id).andExpect(status().isBadRequest());
        dismiss(me, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISMISSED")).andExpect(jsonPath("$.result").doesNotExist());
        assertThat(fix(r2)).isEqualTo("정의의 심판");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/connector/proposals")
                .param("reviewerId", String.valueOf(me)).param("gameId", String.valueOf(gameId))).andExpect(jsonPath("$.length()").value(0));
        verify(bus).publishGameEvent(eq(gameId), eq(ReviewEvents.PROPOSAL_EVENT), argThat(p -> p instanceof ReviewEvents.ProposalEvent e
                && e.type().equals("connector-proposal-decided") && e.status().equals("DISMISSED") && e.proposalId().equals(id)));
        long id2 = propose();
        apply(me, id2, "[" + r2 + "]").andExpect(status().isOk());
        verify(bus).publishGameEvent(eq(gameId), eq(ReviewEvents.PROPOSAL_EVENT), argThat(p -> p instanceof ReviewEvents.ProposalEvent e
                && e.type().equals("connector-proposal-decided") && e.status().equals("APPLIED") && e.proposalId().equals(id2)));
        verify(bus).publishProjectEvent(eq(pid), eq(ReviewEvents.PROPOSAL_EVENT), argThat(p -> p instanceof ReviewEvents.ProposalEvent e
                && e.type().equals("connector-proposal-decided") && e.status().equals("APPLIED") && e.proposalId().equals(id2)));
    }

    @Test
    void applyingTermProposalRecordsCandidate() throws Exception {
        long vp = ConnectorReplaceTest.uploadVariants(mvc);
        long g = jdbc.queryForObject("select game_id from project where id = ?", Long.class, vp);
        long prop = ConnectorReplaceTest.replaceOk(mvc, me, "{\"projectId\":" + vp + ",\"find\":\"함정\",\"replace\":\"올가미\",\"src\":\"陷阱\",\"reason\":\"고객 요청\"}");
        var rows = json(detail(prop)).read("$.rows[*].segmentId", java.util.List.class);
        assertThat(rows).hasSize(4);
        String three = rows.subList(0, 3).toString();
        apply(me, prop, three).andExpect(status().isOk()).andExpect(jsonPath("$.result.saved").value(3));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/games/{g}/glossary/candidates", g))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].src").value("陷阱"))
                .andExpect(jsonPath("$[0].ko").value("올가미"))
                .andExpect(jsonPath("$[0].oldKo").value("함정"))
                .andExpect(jsonPath("$[0].rows").value(3))
                .andExpect(jsonPath("$[0].source").value("CONNECTOR"));
    }

    @Test
    void dismissedOrPlainProposalRecordsNothing() throws Exception {
        long vp = ConnectorReplaceTest.uploadVariants(mvc);
        long g = jdbc.queryForObject("select game_id from project where id = ?", Long.class, vp);
        long a = ConnectorReplaceTest.replaceOk(mvc, me, "{\"projectId\":" + vp + ",\"find\":\"함정\",\"replace\":\"올가미\",\"src\":\"陷阱\"}");
        dismiss(me, a).andExpect(status().isOk());
        long b = ConnectorReplaceTest.replaceOk(mvc, me, "{\"projectId\":" + vp + ",\"find\":\"함정\",\"replace\":\"올가미\"}");
        var rows = json(detail(b)).read("$.rows[*].segmentId", java.util.List.class);
        apply(me, b, rows.toString()).andExpect(status().isOk()).andExpect(jsonPath("$.result.saved").value(4));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/games/{g}/glossary/candidates", g))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
}
