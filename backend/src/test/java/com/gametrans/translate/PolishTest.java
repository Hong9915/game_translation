package com.gametrans.translate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.activity.Activity;
import com.gametrans.activity.ActivityRepository;
import com.gametrans.game.GptInstructions;
import com.gametrans.llm.LlmClient;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** GPT로 다듬기(2026-11-13): 제안만 돌려주는 polish와, 고른 제안을 사람 저장처럼 넣는 save-batch. */
class PolishTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;
    @Autowired SegmentRepository segments;
    @Autowired ActivityRepository activities;
    @Autowired JdbcTemplate jdbc;
    FakeLlmClient fake;

    @BeforeEach
    void setUp() {
        fake = (FakeLlmClient) llm;
        fake.polishFixes.clear();
        fake.polishCalls.clear();
    }

    /** withExistingKoSameSourceTwice(): 2행과 6행의 원문이 같고 한국어는 "정의의 심판". 5행은 한국어가 비어 있다. */
    long normalized() throws Exception {
        long pid = TestProjects.uploadExistingKoSameSourceTwice(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE)).andExpect(status().isOk());
        return pid;
    }

    long row(long pid, int rowNo) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getRowNo() == rowNo).findFirst().map(Segment::getId).orElseThrow();
    }

    String latestKo(long sid) { return segments.findById(sid).orElseThrow().getLatestKo(); }

    void save(long sid, String text) throws Exception {
        mvc.perform(put("/api/segments/{id}/translation", sid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}")).andExpect(status().isOk());
    }

    ResultActions polish(long... ids) throws Exception {
        String body = java.util.Arrays.stream(ids).mapToObj(String::valueOf).collect(Collectors.joining(",", "{\"segmentIds\":[", "]}"));
        return mvc.perform(post("/api/segments/polish").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    static String item(long sid, String text, String base) {
        return "{\"segmentId\":" + sid + ",\"text\":\"" + text + "\",\"base\":" + (base == null ? "null" : "\"" + base + "\"") + "}";
    }

    ResultActions saveBatch(String... items) throws Exception {
        return mvc.perform(post("/api/segments/save-batch").contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[" + String.join(",", items) + "]}"));
    }

    @Test
    void proposesOnlyChangedRows() throws Exception {
        long pid = normalized();
        long r2 = row(pid, 2), r3 = row(pid, 3), r5 = row(pid, 5);
        save(r3, "지금 대상에게 피해를 준다");
        fake.polishFixes.put("정의의 심판", "정의 심판");

        polish(r2, r3, r5).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].segmentId").value(r2))
                .andExpect(jsonPath("$.items[0].before").value("정의의 심판"))
                .andExpect(jsonPath("$.items[0].after").value("정의 심판"))
                .andExpect(jsonPath("$.items[0].reason").value("번역투: 테스트"))
                .andExpect(jsonPath("$.kept").value(1))
                .andExpect(jsonPath("$.skipped.length()").value(1))
                .andExpect(jsonPath("$.skipped[0].id").value(r5))
                .andExpect(jsonPath("$.skipped[0].reason").value("Fix 없음"));

        assertThat(fake.polishCalls).hasSize(1);
        assertThat(fake.polishCalls.get(0).instructions()).startsWith(GptInstructions.POLISH_BASE);
        assertThat(fake.polishCalls.get(0).rows()).hasSize(2);
        // 제안만 — 저장하지 않는다
        assertThat(latestKo(r2)).isEqualTo("정의의 심판");
    }

    @Test
    void polishSkipsConfirmed() throws Exception {
        long pid = normalized();
        long r2 = row(pid, 2);
        jdbc.update("update segment set confirmed = true where id = ?", r2);
        fake.polishFixes.put("정의의 심판", "정의 심판");

        polish(r2).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.skipped[0].reason").value("확정"));
        assertThat(fake.polishCalls).isEmpty();
    }

    @Test
    void polishSkipsBrokenPlaceholder() throws Exception {
        long pid = normalized();
        long r3 = row(pid, 3);
        save(r3, "피해 감소");
        fake.polishFixes.put("피해 감소", "피해 감소 {0}");

        polish(r3).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.skipped[0].reason").value("자리표시자 어긋남"));
    }

    @Test
    void saveBatchPropagatesAndUndoReverts() throws Exception {
        long pid = normalized();
        long r2 = row(pid, 2), r6 = row(pid, 6);

        String body = saveBatch(item(r2, " 정의 심판 ", "정의의 심판")).andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(1))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.propagated").value(1))
                .andExpect(jsonPath("$.activityIds.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(latestKo(r2)).isEqualTo("정의 심판");
        assertThat(latestKo(r6)).isEqualTo("정의 심판");
        List<Number> raw = JsonPath.parse(body).read("$.activityIds");
        List<Long> ids = raw.stream().map(Number::longValue).toList();
        Activity a = activities.findById(ids.get(0)).orElseThrow();
        assertThat(a.getKind()).isEqualTo(Activity.Kind.POLISH);
        assertThat(jdbc.queryForObject("select author from translation_revision where segment_id = ? order by id desc limit 1", String.class, r2))
                .isEqualTo("HUMAN");

        String undoBody = ids.stream().map(String::valueOf).collect(Collectors.joining(",", "{\"activityIds\":[", "]}"));
        DocumentContext r = JsonPath.parse(mvc.perform(post("/api/activity/undo").contentType(MediaType.APPLICATION_JSON).content(undoBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(r.read("$.reverted", Integer.class)).isEqualTo(2);
        assertThat(latestKo(r2)).isEqualTo("정의의 심판");
        assertThat(latestKo(r6)).isEqualTo("정의의 심판");
    }

    @Test
    void saveBatchSkipsConfirmedAndChanged() throws Exception {
        long pid = normalized();
        long r2 = row(pid, 2), r3 = row(pid, 3);
        save(r3, "지금 대상에게 피해를 준다");
        jdbc.update("update segment set confirmed = true where id = ?", r2);

        saveBatch(item(r2, "정의 심판", "정의의 심판"), item(r3, "대상에게 피해", "예전 글")).andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(0))
                .andExpect(jsonPath("$.skipped").value(2))
                .andExpect(jsonPath("$.activityIds.length()").value(0));
        assertThat(latestKo(r2)).isEqualTo("정의의 심판");
        assertThat(latestKo(r3)).isEqualTo("지금 대상에게 피해를 준다");
    }

    @Test
    void saveBatchRejectsEmptyAndTwoGames() throws Exception {
        long a = normalized(), b = normalized();
        saveBatch().andExpect(status().isBadRequest());
        saveBatch(item(row(a, 2), "가", "정의의 심판"), item(row(b, 2), "나", "정의의 심판")).andExpect(status().isBadRequest());
        assertThat(latestKo(row(a, 2))).isEqualTo("정의의 심판");
    }
    @Test
    void polishKeepsWhitespaceOnlyChange() throws Exception {
        long pid = normalized();
        long r3 = row(pid, 3);
        save(r3, "피해 감소");
        // 기존 한국어에 끝 공백이 남은 행 — GPT가 공백만 지운 안은 바뀐 것으로 보지 않는다
        jdbc.update("update translation_revision set text = '피해 감소 ' where segment_id = ?", r3);
        jdbc.update("update segment set latest_ko = '피해 감소 ' where id = ?", r3);
        fake.polishFixes.put("피해 감소 ", "피해 감소");

        polish(r3).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.kept").value(1));
    }

    @Test
    void saveBatchRejectsNullSegmentId() throws Exception {
        long pid = normalized();
        saveBatch(item(row(pid, 2), "정의 심판", "정의의 심판"), "{\"segmentId\":null,\"text\":\"가\",\"base\":null}")
                .andExpect(status().isBadRequest());
        assertThat(latestKo(row(pid, 2))).isEqualTo("정의의 심판");
    }
}
