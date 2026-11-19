package com.gametrans.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.activity.Activity;
import com.gametrans.activity.ActivityRepository;
import com.gametrans.activity.ActivityService;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 커넥터 되돌리기(/api/connector/undo)는 부른 검수자가 커넥터로 직접 고친 것(CONNECTOR)과, 그걸 되돌리고 다시 실행한 REVERT만 받는다.
 * 다른 사람의 항목·다른 종류(JOSA_FIX·POLISH·EDIT)·그것들의 REVERT가 하나라도 섞이면 400이고 아무것도 되돌리지 않는다.
 * finalSameSourceTwice(): 2행·3행 원문이 같아(따라 바뀜) Fix "정의의 심판", 4행 "공격".
 */
class ConnectorUndoScopeTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ActivityRepository activities;
    @Autowired ActivityService activityService;

    long pid, r1, r2, r3, me, other;

    @BeforeEach
    void setUp() throws Exception {
        pid = TestProjects.uploadFinalSameSourceTwice(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.FINAL_SKILL_NORMALIZE)).andExpect(status().isOk());
        r1 = row(2);
        r2 = row(3);
        r3 = row(4);
        me = ConnectorSaveTest.newReviewer(mvc);
        other = ConnectorSaveTest.newReviewer(mvc);
    }

    long row(int rowNo) {
        return jdbc.queryForObject("select id from segment where project_id = ? and row_no = ? and skipped = false order by id limit 1",
                Long.class, pid, rowNo);
    }

    String text(long sid) { return jdbc.queryForObject("select latest_ko from segment where id = ?", String.class, sid); }

    static String item(long sid, String text, String base) {
        return "{\"segmentId\":" + sid + ",\"text\":\"" + text + "\",\"base\":" + (base == null ? "null" : "\"" + base + "\"") + "}";
    }

    static DocumentContext ok(ResultActions a) throws Exception {
        return JsonPath.parse(a.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    static List<Long> ids(DocumentContext r) {
        List<Number> raw = r.read("$.activityIds");
        return raw.stream().map(Number::longValue).toList();
    }

    /** source가 null이면 웹 저장(POLISH). */
    List<Long> save(long by, String source, String... items) throws Exception {
        String src = source == null ? "" : ",\"source\":\"" + source + "\"";
        return ids(ok(mvc.perform(post("/api/segments/save-batch").header("X-Reviewer-Id", by).contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[" + String.join(",", items) + "]" + src + "}"))));
    }

    static String body(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "{\"activityIds\":[", "]}"));
    }

    /** by가 null이면 X-Reviewer-Id 헤더 없이 보낸다. */
    ResultActions connectorUndo(Long by, List<Long> ids) throws Exception {
        var req = post("/api/connector/undo").contentType(MediaType.APPLICATION_JSON).content(body(ids));
        if (by != null) req.header("X-Reviewer-Id", by);
        return mvc.perform(req);
    }

    void expectRejected(ResultActions a, long badId) throws Exception {
        a.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString(": " + badId + " ")))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("커넥터로 직접 고친 것만")));
    }

    @Test
    void ownConnectorIdUndoes() throws Exception {
        List<Long> saved = save(me, "CONNECTOR", item(r1, "정의 심판", "정의의 심판"));

        DocumentContext u = ok(connectorUndo(me, saved));

        assertThat(u.read("$.reverted", Integer.class)).isEqualTo(2);
        assertThat(text(r1)).isEqualTo("정의의 심판");
        assertThat(text(r2)).isEqualTo("정의의 심판");
        assertThat(ids(u)).hasSize(1);
    }

    @Test
    void redoViaReturnedRevertIdsWorks() throws Exception {
        List<Long> saved = save(me, "CONNECTOR", item(r1, "정의 심판", "정의의 심판"));
        DocumentContext u = ok(connectorUndo(me, saved));

        DocumentContext redo = ok(connectorUndo(me, ids(u)));
        assertThat(text(r1)).isEqualTo("정의 심판");

        // 다시 실행이 만든 REVERT(사슬: REVERT → REVERT → CONNECTOR)도 또 되돌릴 수 있다
        ok(connectorUndo(me, ids(redo)));
        assertThat(text(r1)).isEqualTo("정의의 심판");
    }

    @Test
    void redoOfFirstVersionRowReturnsOriginalIdAndItIsAllowed() throws Exception {
        jdbc.update("delete from translation_revision where segment_id = ?", r3);   // 원래 빈 칸인 행
        List<Long> saved = save(me, "CONNECTOR", item(r3, "공격 개시", null));
        DocumentContext u = ok(connectorUndo(me, saved));
        assertThat(text(r3)).isNull();

        DocumentContext redo = ok(connectorUndo(me, ids(u)));
        assertThat(text(r3)).isEqualTo("공격 개시");

        ok(connectorUndo(me, ids(redo)));
        assertThat(text(r3)).isNull();
    }

    @Test
    void otherReviewersConnectorIdIs400() throws Exception {
        List<Long> theirs = save(other, "CONNECTOR", item(r1, "정의 심판", "정의의 심판"));

        expectRejected(connectorUndo(me, theirs), theirs.get(0));

        assertThat(text(r1)).isEqualTo("정의 심판");
        assertThat(text(r2)).isEqualTo("정의 심판");
    }

    @Test
    void josaFixIdIs400() throws Exception {
        long josa = activityService.start(pid, Activity.Kind.JOSA_FIX, me, Map.of());
        expectRejected(connectorUndo(me, List.of(josa)), josa);
    }

    @Test
    void polishIdIs400() throws Exception {
        List<Long> polish = save(me, null, item(r1, "정의 심판", "정의의 심판"));

        expectRejected(connectorUndo(me, polish), polish.get(0));

        assertThat(text(r1)).isEqualTo("정의 심판");
    }

    @Test
    void editIdIs400() throws Exception {
        mvc.perform(put("/api/segments/{id}/translation", r3).header("X-Reviewer-Id", me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"공격하기\"}")).andExpect(status().isOk());
        Activity edit = activities.findByProjectIdOrderByIdDesc(pid).get(0);
        assertThat(edit.getKind()).isEqualTo(Activity.Kind.EDIT);

        expectRejected(connectorUndo(me, List.of(edit.getId())), edit.getId());

        assertThat(text(r3)).isEqualTo("공격하기");
    }

    @Test
    void revertOfPolishIs400() throws Exception {
        List<Long> polish = save(me, null, item(r1, "정의 심판", "정의의 심판"));
        // 웹 Ctrl+Z로 되돌린 REVERT — 사슬 끝이 CONNECTOR가 아니다
        List<Long> revert = ids(ok(mvc.perform(post("/api/activity/undo").header("X-Reviewer-Id", me)
                .contentType(MediaType.APPLICATION_JSON).content(body(polish)))));
        assertThat(text(r1)).isEqualTo("정의의 심판");

        expectRejected(connectorUndo(me, revert), revert.get(0));

        assertThat(text(r1)).isEqualTo("정의의 심판");
    }

    @Test
    void mixedListWithOneBadIdUndoesNothing() throws Exception {
        List<Long> mine = save(me, "CONNECTOR", item(r1, "정의 심판", "정의의 심판"));
        List<Long> polish = save(me, null, item(r3, "공격하기", "공격"));

        expectRejected(connectorUndo(me, List.of(mine.get(0), polish.get(0))), polish.get(0));

        assertThat(text(r1)).isEqualTo("정의 심판");
        assertThat(text(r2)).isEqualTo("정의 심판");
        assertThat(text(r3)).isEqualTo("공격하기");
    }

    @Test
    void over50IdsIs400() throws Exception {
        List<Long> mine = save(me, "CONNECTOR", item(r1, "정의 심판", "정의의 심판"));
        List<Long> many = LongStream.range(0, 51).map(i -> mine.get(0)).boxed().toList();

        connectorUndo(me, many).andExpect(status().isBadRequest());
        connectorUndo(me, List.of()).andExpect(status().isBadRequest());

        assertThat(text(r1)).isEqualTo("정의 심판");
    }

    @Test
    void unknownIdIs404() throws Exception {
        connectorUndo(me, List.of(999_999_999L)).andExpect(status().isNotFound());
    }

    @Test
    void withoutReviewerHeaderIs400() throws Exception {
        List<Long> mine = save(me, "CONNECTOR", item(r1, "정의 심판", "정의의 심판"));

        connectorUndo(null, mine).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ConnectorSaveTest.NO_REVIEWER));

        assertThat(text(r1)).isEqualTo("정의 심판");
    }

    @Test
    void deletedReviewerIs400() throws Exception {
        List<Long> mine = save(me, "CONNECTOR", item(r1, "정의 심판", "정의의 심판"));

        connectorUndo(ConnectorSaveTest.deletedReviewer(mvc), mine).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ConnectorSaveTest.NO_REVIEWER));

        assertThat(text(r1)).isEqualTo("정의 심판");
    }
}
