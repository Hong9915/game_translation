package com.gametrans.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.activity.Activity;
import com.gametrans.activity.ActivityRepository;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** save-batch의 출처(CONNECTOR)·건너뛴 행 이유·최종 리뷰 레인 최종 수정 자동 켜기·검수자 필수·이미 같은 글(unchanged). */
class ConnectorSaveTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ActivityRepository activities;

    static final String NO_REVIEWER = "검수자를 찾을 수 없습니다. Claude에서 커넥터를 다시 연결해 주세요.";

    long normalPid, finalPid, reviewerId;

    @BeforeEach
    void setUp() throws Exception {
        String game = TestProjects.freshGame();
        normalPid = TestProjects.uploadExistingKoSameSourceTwice(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", normalPid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE)).andExpect(status().isOk());
        finalPid = TestProjects.uploadFinalSameSourceTwice(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", finalPid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.FINAL_SKILL_NORMALIZE)).andExpect(status().isOk());
        reviewerId = newReviewer(mvc);
    }

    /** 새 검수자를 만들고 id를 돌려준다. */
    static long newReviewer(MockMvc mvc) throws Exception {
        String body = mvc.perform(post("/api/reviewers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"커넥터검수자-" + UUID.randomUUID().toString().substring(0, 8) + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body).read("$.id", Long.class);
    }

    /** 만들었다가 바로 지운 검수자 id — 커넥터 계정 캐시(60초) 안에 검수자가 지워진 경우. */
    static long deletedReviewer(MockMvc mvc) throws Exception {
        long id = newReviewer(mvc);
        mvc.perform(delete("/api/reviewers/{id}", id)).andExpect(status().isNoContent());
        return id;
    }

    long row(long pid, int rowNo) {
        return jdbc.queryForObject("select id from segment where project_id = ? and row_no = ? and skipped = false order by id limit 1",
                Long.class, pid, rowNo);
    }

    boolean mark(long sid) { return jdbc.queryForObject("select final_mark from segment where id = ?", Boolean.class, sid); }

    static String item(long sid, String text, String base) {
        return "{\"segmentId\":" + sid + ",\"text\":\"" + text + "\",\"base\":" + (base == null ? "null" : "\"" + base + "\"") + "}";
    }

    ResultActions save(String source, String... items) throws Exception {
        return saveAs(reviewerId, source, items);
    }

    /** by가 null이면 X-Reviewer-Id 헤더 없이 보낸다. */
    ResultActions saveAs(Long by, String source, String... items) throws Exception {
        String src = source == null ? "" : ",\"source\":\"" + source + "\"";
        var req = post("/api/segments/save-batch").contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[" + String.join(",", items) + "]" + src + "}");
        if (by != null) req.header("X-Reviewer-Id", by);
        return mvc.perform(req);
    }

    String text(long sid) { return jdbc.queryForObject("select latest_ko from segment where id = ?", String.class, sid); }

    long activityCount() { return jdbc.queryForObject("select count(*) from activity", Long.class); }

    DocumentContext ok(ResultActions a) throws Exception {
        return JsonPath.parse(a.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    Activity activity(DocumentContext r, int i) {
        return activities.findById(r.read("$.activityIds[" + i + "]", Long.class)).orElseThrow();
    }

    @Test
    void connectorSourceRecordsConnectorKind() throws Exception {
        DocumentContext r = ok(save("CONNECTOR", item(row(normalPid, 2), "정의 심판", "정의의 심판")));
        assertThat(activity(r, 0).getKind()).isEqualTo(Activity.Kind.CONNECTOR);
    }

    @Test
    void noSourceStaysPolish() throws Exception {
        DocumentContext r = ok(save(null, item(row(normalPid, 2), "정의 심판", "정의의 심판")));
        assertThat(activity(r, 0).getKind()).isEqualTo(Activity.Kind.POLISH);
    }

    @Test
    void skippedRowsCarryReasons() throws Exception {
        long locked = row(normalPid, 2), changed = row(normalPid, 3), empty = row(normalPid, 6);
        jdbc.update("update segment set confirmed = true where id = ?", locked);
        DocumentContext r = ok(save("CONNECTOR", item(locked, "가", "정의의 심판"), item(changed, "나", "예전 글"), item(empty, "  ", "정의의 심판")));
        assertThat(r.read("$.skipped", Integer.class)).isEqualTo(3);
        assertThat(r.read("$.skippedRows[?(@.id==" + locked + ")].reason", List.class)).containsExactly("확정");
        assertThat(r.read("$.skippedRows[?(@.id==" + changed + ")].reason", List.class)).containsExactly("그 사이 바뀜");
        assertThat(r.read("$.skippedRows[?(@.id==" + empty + ")].reason", List.class)).containsExactly("빈 글");
    }

    @Test
    void finalLaneRowGetsFinalMark() throws Exception {
        long r1 = row(finalPid, 2), r2 = row(finalPid, 3);
        DocumentContext r = ok(save("CONNECTOR", item(r1, "정의 심판", "정의의 심판")));
        assertThat(mark(r1)).isTrue();
        assertThat(mark(r2)).isTrue();
        assertThat(r.read("$.finalMarked", Integer.class)).isEqualTo(2);
        List<Number> marked = (List<Number>) activity(r, 0).getDetail().get("finalMarked");
        assertThat(marked.stream().map(Number::longValue).toList()).containsExactlyInAnyOrder(r1, r2);
    }

    @Test
    void propagatedFinalLaneRowAlsoMarked() throws Exception {
        long r1 = row(finalPid, 2), r2 = row(finalPid, 3);
        ok(save("CONNECTOR", item(r1, "정의 심판", "정의의 심판")));
        assertThat(jdbc.queryForObject("select latest_ko from segment where id = ?", String.class, r2)).isEqualTo("정의 심판");
        assertThat(mark(r2)).isTrue();
    }

    @Test
    void normalLaneRowNotMarked() throws Exception {
        long r = row(normalPid, 2);
        DocumentContext res = ok(save("CONNECTOR", item(r, "정의 심판", "정의의 심판")));
        assertThat(mark(r)).isFalse();
        assertThat(res.read("$.finalMarked", Integer.class)).isEqualTo(0);
    }

    @Test
    void polishSaveDoesNotMark() throws Exception {
        long r = row(finalPid, 2);
        ok(save(null, item(r, "정의 심판", "정의의 심판")));
        assertThat(mark(r)).isFalse();
    }

    @Test
    void alreadyMarkedRowNotListed() throws Exception {
        long r1 = row(finalPid, 2), r2 = row(finalPid, 3);
        jdbc.update("update segment set final_mark = true where id = ?", r1);
        DocumentContext r = ok(save("CONNECTOR", item(r1, "정의 심판", "정의의 심판")));
        assertThat(r.read("$.finalMarked", Integer.class)).isEqualTo(1);
        List<Number> marked = (List<Number>) activity(r, 0).getDetail().get("finalMarked");
        assertThat(marked.stream().map(Number::longValue).toList()).containsExactly(r2);
        assertThat(mark(r1)).isTrue();
    }

    @Test
    void skippedRowNotMarked() throws Exception {
        long r = row(finalPid, 4);
        jdbc.update("update segment set confirmed = true where id = ?", r);
        ok(save("CONNECTOR", item(r, "공 격", "공격")));
        assertThat(mark(r)).isFalse();
    }

    @Test
    void connectorSaveWithoutReviewerHeaderIs400() throws Exception {
        long r = row(finalPid, 2);
        long before = activityCount();
        saveAs(null, "CONNECTOR", item(r, "정의 심판", "정의의 심판")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(NO_REVIEWER));
        assertThat(text(r)).isEqualTo("정의의 심판");
        assertThat(mark(r)).isFalse();
        assertThat(activityCount()).isEqualTo(before);
    }

    @Test
    void connectorSaveWithDeletedReviewerIs400() throws Exception {
        long r = row(finalPid, 2);
        long before = activityCount();
        saveAs(deletedReviewer(mvc), "CONNECTOR", item(r, "정의 심판", "정의의 심판")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(NO_REVIEWER));
        assertThat(text(r)).isEqualTo("정의의 심판");
        assertThat(activityCount()).isEqualTo(before);
    }

    @Test
    void webSaveWithoutHeaderStillWorks() throws Exception {
        long r = row(normalPid, 2);
        ok(saveAs(null, null, item(r, "정의 심판", "정의의 심판")));
        assertThat(text(r)).isEqualTo("정의 심판");
    }

    @Test
    void sameTextCountedAsUnchanged() throws Exception {
        long r1 = row(finalPid, 2), r3 = row(finalPid, 4);
        DocumentContext r = ok(save("CONNECTOR", item(r1, "정의 심판", "정의의 심판"), item(r3, "공격", "공격")));
        assertThat(r.read("$.saved", Integer.class)).isEqualTo(1);
        assertThat(r.read("$.unchanged", Integer.class)).isEqualTo(1);
        assertThat(r.read("$.skipped", Integer.class)).isEqualTo(0);
        assertThat(mark(r3)).isFalse();
    }

    @Test
    void rejectsUnknownSource() throws Exception {
        save("FOO", item(row(normalPid, 2), "정의 심판", "정의의 심판")).andExpect(status().isBadRequest());
    }
}
