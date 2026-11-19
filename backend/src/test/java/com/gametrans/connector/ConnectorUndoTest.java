package com.gametrans.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.activity.Activity;
import com.gametrans.activity.ActivityRepository;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 커넥터 저장을 되돌리면(Ctrl+Z·undo·패널 되돌리기) 그 저장이 새로 켠 최종 수정도 끄고, 다시 실행하면 다시 켠다.
 * finalSameSourceTwice(): 최종 리뷰 레인, 2행·3행 원문이 같아(따라 바뀜) Fix "정의의 심판", 4행 "공격".
 */
class ConnectorUndoTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ActivityRepository activities;

    String game;
    long pid, r1, r2, r3, reviewerId;

    @BeforeEach
    void setUp() throws Exception {
        game = TestProjects.freshGame();
        pid = TestProjects.uploadFinalSameSourceTwice(mvc, game);
        normalize(pid);
        r1 = row(pid, 2);
        r2 = row(pid, 3);
        r3 = row(pid, 4);
        reviewerId = ConnectorSaveTest.newReviewer(mvc);   // 커넥터 저장은 검수자 헤더가 꼭 있어야 한다
    }

    void normalize(long project) throws Exception {
        mvc.perform(put("/api/projects/{id}/normalize", project).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.FINAL_SKILL_NORMALIZE)).andExpect(status().isOk());
    }

    long row(long project, int rowNo) {
        return jdbc.queryForObject("select id from segment where project_id = ? and row_no = ? and skipped = false order by id limit 1",
                Long.class, project, rowNo);
    }

    boolean mark(long sid) { return jdbc.queryForObject("select final_mark from segment where id = ?", Boolean.class, sid); }

    String text(long sid) { return jdbc.queryForObject("select latest_ko from segment where id = ?", String.class, sid); }

    static String item(long sid, String text, String base) {
        return "{\"segmentId\":" + sid + ",\"text\":\"" + text + "\",\"base\":" + (base == null ? "null" : "\"" + base + "\"") + "}";
    }

    /** MockMvc는 응답을 ISO-8859-1로 읽어 한글이 깨지므로 UTF-8로 읽는다. */
    static DocumentContext ok(ResultActions a) throws Exception {
        return JsonPath.parse(a.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    DocumentContext save(String source, String... items) throws Exception {
        String src = source == null ? "" : ",\"source\":\"" + source + "\"";
        return ok(mvc.perform(post("/api/segments/save-batch").header("X-Reviewer-Id", reviewerId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[" + String.join(",", items) + "]" + src + "}")));
    }

    DocumentContext undo(List<Long> ids) throws Exception {
        String json = ids.stream().map(String::valueOf).collect(Collectors.joining(",", "{\"activityIds\":[", "]}"));
        return ok(mvc.perform(post("/api/activity/undo").contentType(MediaType.APPLICATION_JSON).content(json)));
    }

    DocumentContext revert(long activityId) throws Exception {
        return ok(mvc.perform(post("/api/activity/{id}/revert", activityId)));
    }

    /** 다른 사람이 그 행을 고친다(사람 저장). */
    void edit(long sid, String text) throws Exception {
        mvc.perform(put("/api/segments/{id}/translation", sid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}")).andExpect(status().isOk());
    }

    static List<Long> ids(DocumentContext r) {
        List<Number> raw = r.read("$.activityIds");
        return raw.stream().map(Number::longValue).toList();
    }

    List<Long> detail(long activityId, String key) {
        Object o = activities.findById(activityId).orElseThrow().getDetail().get(key);
        return o == null ? List.of() : ((List<?>) o).stream().map(n -> ((Number) n).longValue()).toList();
    }

    Activity latest(long project) { return activities.findByProjectIdOrderByIdDesc(project).get(0); }

    @Test
    void undoTurnsFinalMarkOff() throws Exception {
        DocumentContext saved = save("CONNECTOR", item(r1, "정의 심판", "정의의 심판"));
        assertThat(mark(r1)).isTrue();
        assertThat(mark(r2)).isTrue();

        DocumentContext u = undo(ids(saved));

        assertThat(u.read("$.reverted", Integer.class)).isEqualTo(2);
        assertThat(text(r1)).isEqualTo("정의의 심판");
        assertThat(text(r2)).isEqualTo("정의의 심판");
        assertThat(mark(r1)).isFalse();
        assertThat(mark(r2)).isFalse();
        assertThat(ids(u)).hasSize(1);
        assertThat(detail(ids(u).get(0), "finalUnmarked")).containsExactlyInAnyOrder(r1, r2);
    }

    @Test
    void redoTurnsFinalMarkBackOn() throws Exception {
        DocumentContext saved = save("CONNECTOR", item(r1, "정의 심판", "정의의 심판"));
        DocumentContext u = undo(ids(saved));

        DocumentContext redo = undo(ids(u));

        assertThat(text(r1)).isEqualTo("정의 심판");
        assertThat(text(r2)).isEqualTo("정의 심판");
        assertThat(mark(r1)).isTrue();
        assertThat(mark(r2)).isTrue();
        assertThat(ids(redo)).hasSize(1);
        assertThat(detail(ids(redo).get(0), "finalMarked")).containsExactlyInAnyOrder(r1, r2);

        // 다시 실행을 또 되돌리면 또 꺼진다 — 되돌림과 다시 실행이 대칭으로 이어진다.
        undo(ids(redo));
        assertThat(text(r1)).isEqualTo("정의의 심판");
        assertThat(mark(r1)).isFalse();
        assertThat(mark(r2)).isFalse();
    }

    @Test
    void preMarkedRowStaysMarkedAfterUndo() throws Exception {
        jdbc.update("update segment set final_mark = true where id = ?", r1);
        DocumentContext saved = save("CONNECTOR", item(r1, "정의 심판", "정의의 심판"));

        undo(ids(saved));

        assertThat(text(r1)).isEqualTo("정의의 심판");
        assertThat(mark(r1)).isTrue();
        assertThat(mark(r2)).isFalse();
    }

    @Test
    void panelRevertTurnsFinalMarkOff() throws Exception {
        DocumentContext saved = save("CONNECTOR", item(r1, "정의 심판", "정의의 심판"));

        DocumentContext r = revert(ids(saved).get(0));

        assertThat(r.read("$.reverted", Integer.class)).isEqualTo(2);
        assertThat(text(r1)).isEqualTo("정의의 심판");
        assertThat(mark(r1)).isFalse();
        assertThat(mark(r2)).isFalse();
        Activity rev = latest(pid);
        assertThat(rev.getKind()).isEqualTo(Activity.Kind.REVERT);
        assertThat(detail(rev.getId(), "finalUnmarked")).containsExactlyInAnyOrder(r1, r2);
    }

    @Test
    void panelRevertOfRevertTurnsFinalMarkBackOn() throws Exception {
        DocumentContext saved = save("CONNECTOR", item(r1, "정의 심판", "정의의 심판"));
        revert(ids(saved).get(0));
        long first = latest(pid).getId();

        revert(first);

        assertThat(text(r1)).isEqualTo("정의 심판");
        assertThat(mark(r1)).isTrue();
        assertThat(mark(r2)).isTrue();
        Activity rev = latest(pid);
        assertThat(rev.getId()).isNotEqualTo(first);
        assertThat(detail(rev.getId(), "finalMarked")).containsExactlyInAnyOrder(r1, r2);
    }

    @Test
    void rowChangedSinceIsNotUnmarked() throws Exception {
        DocumentContext saved = save("CONNECTOR", item(r1, "정의 심판", "정의의 심판"), item(r3, "공격 개시", "공격"));
        assertThat(mark(r3)).isTrue();
        edit(r3, "다른 사람 글");

        DocumentContext u = undo(ids(saved));

        assertThat(u.read("$.reverted", Integer.class)).isEqualTo(2);
        assertThat(u.read("$.skipped", Integer.class)).isEqualTo(1);
        assertThat(text(r3)).isEqualTo("다른 사람 글");
        assertThat(mark(r3)).isTrue();
        assertThat(mark(r1)).isFalse();
        assertThat(mark(r2)).isFalse();
        assertThat(detail(ids(u).get(0), "finalUnmarked")).containsExactlyInAnyOrder(r1, r2);
    }

    @Test
    void polishUndoUnchanged() throws Exception {
        jdbc.update("update segment set final_mark = true where id in (?, ?)", r1, r2);   // 사람이 켜 둔 최종 수정
        DocumentContext saved = save(null, item(r1, "정의 심판", "정의의 심판"));

        undo(ids(saved));

        assertThat(text(r1)).isEqualTo("정의의 심판");
        assertThat(mark(r1)).isTrue();
        assertThat(mark(r2)).isTrue();
    }

    @Test
    void firstVersionRowClearedThenRefilled() throws Exception {
        jdbc.update("delete from translation_revision where segment_id = ?", r3);   // 원래 빈 칸인 행
        assertThat(text(r3)).isNull();
        DocumentContext saved = save("CONNECTOR", item(r3, "공격 개시", null));
        assertThat(mark(r3)).isTrue();

        DocumentContext u = undo(ids(saved));
        assertThat(text(r3)).isNull();
        assertThat(mark(r3)).isFalse();

        DocumentContext redo = undo(ids(u));
        assertThat(text(r3)).isEqualTo("공격 개시");
        assertThat(mark(r3)).isTrue();

        undo(ids(redo));
        assertThat(text(r3)).isNull();
        assertThat(mark(r3)).isFalse();
    }

    @Test
    void saveSpanningTwoFilesUndoesEachFile() throws Exception {
        long other = TestProjects.uploadExistingKoAsFinal(mvc, game);   // 같은 게임, 2행 원문이 같다
        normalize(other);
        long o = row(other, 2);
        String before = text(o);
        DocumentContext saved = save("CONNECTOR", item(r1, "정의 심판", "정의의 심판"));
        assertThat(ids(saved)).hasSize(2);
        assertThat(mark(o)).isTrue();

        DocumentContext u = undo(ids(saved));

        assertThat(text(o)).isEqualTo(before);
        assertThat(mark(o)).isFalse();
        assertThat(mark(r1)).isFalse();
        assertThat(ids(u)).hasSize(2);
        for (Long rev : ids(u)) {
            Activity a = activities.findById(rev).orElseThrow();
            if (a.getProjectId().equals(pid)) assertThat(detail(rev, "finalUnmarked")).containsExactlyInAnyOrder(r1, r2);
            else assertThat(detail(rev, "finalUnmarked")).containsExactly(o);
        }
    }
}
