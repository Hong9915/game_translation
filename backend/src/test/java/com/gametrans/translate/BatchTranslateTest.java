package com.gametrans.translate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gametrans.FakeLlmClient;
import com.gametrans.FakeMtClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.deepl.MtClient;
import com.gametrans.game.GptInstructions;
import com.gametrans.llm.LlmClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 여러 행 다시 번역(2026-11-13). 설정은 DeepL이어도 요청의 engine을 따른다. */
@TestPropertySource(properties = "app.translate.engine=deepl")
class BatchTranslateTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MtClient mt;
    @Autowired LlmClient llm;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    FakeMtClient fakeMt;
    FakeLlmClient fakeLlm;

    @BeforeEach
    void setUp() {
        fakeMt = (FakeMtClient) mt; fakeMt.reset(); jdbc.update("delete from deepl_cache");
        fakeLlm = (FakeLlmClient) llm; fakeLlm.translateCalls.clear(); fakeLlm.translations.clear();
    }

    private long sid(long pid, String src) {
        return jdbc.queryForObject("select id from segment where project_id = ? and src_text = ?", Long.class, pid, src);
    }

    private long fresh() throws Exception {
        long pid = TestProjects.upload(mvc, TestProjects.freshGame());
        TestProjects.normalize(mvc, pid);
        return pid;
    }

    private ResultActions call(String body) throws Exception {
        return mvc.perform(post("/api/segments/translate-batch").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Map<String, Object> latest(long sid) {
        return jdbc.queryForMap("""
                select s.latest_ko, r.author, r.activity_id
                from segment s left join lateral (select author, activity_id from translation_revision where segment_id = s.id order by id desc limit 1) r on true
                where s.id = ?""", sid);
    }

    @Test
    void translatesWithGptRegardlessOfEngineSetting() throws Exception {
        long pid = fresh();
        long sid = sid(pid, "烈焰之剑");
        fakeLlm.translations.put("烈焰之剑", "불꽃의 검");
        call("{\"segmentIds\":[" + sid + "],\"engine\":\"GPT\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].id").value(sid))
                .andExpect(jsonPath("$.rows[0].fix").value("불꽃의 검"))
                .andExpect(jsonPath("$.activityIds['" + pid + "']").isNumber());
        assertThat(latest(sid).get("latest_ko")).isEqualTo("불꽃의 검");
        assertThat(latest(sid).get("author")).isEqualTo("LLM");
        assertThat(fakeLlm.translateCalls).hasSize(1);
        assertThat(fakeLlm.lastInstructions()).startsWith(GptInstructions.TRANSLATE_BASE);
        assertThat(fakeMt.calls).isEmpty();
    }

    @Test
    void translatesWithDeepL() throws Exception {
        long pid = fresh();
        long a = sid(pid, "烈焰之剑"), b = sid(pid, "霜语者");
        fakeMt.answers.put("烈焰之剑", "타오르는 검");
        fakeMt.answers.put("霜语者", "서리속삭임");
        call("{\"segmentIds\":[" + a + "," + b + "],\"engine\":\"DEEPL\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(2)).andExpect(jsonPath("$.skipped.length()").value(0));
        assertThat(latest(a).get("latest_ko")).isEqualTo("타오르는 검");
        assertThat(latest(a).get("author")).isEqualTo("DEEPL");
        assertThat(latest(b).get("latest_ko")).isEqualTo("서리속삭임");
        assertThat(fakeMt.calls).hasSize(1);
        assertThat(fakeMt.sourceLangs).containsExactly("ZH");
        assertThat(fakeLlm.translateCalls).isEmpty();
        // 행 둘이 activity 하나에 매달린다
        assertThat(latest(a).get("activity_id")).isEqualTo(latest(b).get("activity_id"));
        assertThat(jdbc.queryForObject("select kind from activity where id = ?", String.class, latest(a).get("activity_id"))).isEqualTo("RETRANSLATE");
    }

    @Test
    void skipsConfirmed() throws Exception {
        long pid = fresh();
        long ok = sid(pid, "烈焰之剑"), confirmed = sid(pid, "霜语者"), excluded = sid(pid, "使用后恢复{0}点生命值，冷却{1}秒。");
        jdbc.update("update segment set confirmed = true where id = ?", confirmed);
        jdbc.update("update segment set skipped = true where id = ?", excluded);
        call("{\"segmentIds\":[" + ok + "," + confirmed + "," + excluded + "],\"engine\":\"GPT\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.skipped.length()").value(2))
                .andExpect(jsonPath("$.skipped[?(@.id == " + confirmed + ")].reason").value("확정"))
                .andExpect(jsonPath("$.skipped[?(@.id == " + excluded + ")].reason").value("제외"));
        assertThat(latest(confirmed).get("latest_ko")).isNull();
        assertThat(latest(excluded).get("latest_ko")).isNull();
        assertThat(fakeLlm.translateCalls.get(0).rows()).hasSize(1);
    }

    @Test
    void skipsRowWithNoResult() throws Exception {
        long pid = fresh();
        long a = sid(pid, "烈焰之剑");
        fakeLlm.dropOnce.add(String.valueOf(a));
        call("{\"segmentIds\":[" + a + "],\"engine\":\"GPT\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(0))
                .andExpect(jsonPath("$.skipped[0].reason").value("번역 결과 없음"));
        assertThat(latest(a).get("latest_ko")).isNull();
    }

    @Test
    void reusesActivityAcrossChunks() throws Exception {
        long pid = fresh();
        long a = sid(pid, "烈焰之剑"), b = sid(pid, "霜语者");
        String first = call("{\"segmentIds\":[" + a + "],\"engine\":\"DEEPL\"}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode ids = json.readTree(first).get("activityIds");
        long aid = ids.get(String.valueOf(pid)).asLong();
        call("{\"segmentIds\":[" + b + "],\"engine\":\"DEEPL\",\"activityIds\":" + ids + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.activityIds['" + pid + "']").value(aid));
        assertThat(((Number) latest(a).get("activity_id")).longValue()).isEqualTo(aid);
        assertThat(((Number) latest(b).get("activity_id")).longValue()).isEqualTo(aid);
        assertThat(jdbc.queryForObject("select count(*) from activity where project_id = ? and kind = 'RETRANSLATE'", Long.class, pid)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(distinct segment_id) from translation_revision where activity_id = ?", Long.class, aid)).isEqualTo(2L);
    }

    @Test
    void ignoresActivityOfAnotherProject() throws Exception {
        long p1 = fresh(), p2 = fresh();
        String first = call("{\"segmentIds\":[" + sid(p1, "烈焰之剑") + "],\"engine\":\"DEEPL\"}").andReturn().getResponse().getContentAsString();
        long foreign = json.readTree(first).get("activityIds").get(String.valueOf(p1)).asLong();
        long b = sid(p2, "烈焰之剑");
        call("{\"segmentIds\":[" + b + "],\"engine\":\"DEEPL\",\"activityIds\":{\"" + p2 + "\":" + foreign + "}}").andExpect(status().isOk());
        long used = ((Number) latest(b).get("activity_id")).longValue();
        assertThat(used).isNotEqualTo(foreign);
        assertThat(jdbc.queryForObject("select project_id from activity where id = ?", Long.class, used)).isEqualTo(p2);
    }

    @Test
    void doesNotPropagate() throws Exception {
        String game = TestProjects.freshGame();
        long p1 = TestProjects.upload(mvc, game);
        TestProjects.normalize(mvc, p1);
        long p2 = TestProjects.upload(mvc, game);
        TestProjects.normalize(mvc, p2);
        long target = sid(p1, "霜语者");
        call("{\"segmentIds\":[" + target + "],\"engine\":\"GPT\"}").andExpect(status().isOk());
        assertThat(latest(target).get("latest_ko")).isEqualTo("KO:霜语者");
        List<String> others = jdbc.queryForList("select coalesce(latest_ko, '') from segment where src_text = '霜语者' and id <> ? and project_id in (?, ?)",
                String.class, target, p1, p2);
        assertThat(others).isNotEmpty().allMatch(String::isEmpty);
        assertThat(jdbc.queryForObject("select count(*) from translation_memory where game_name = ? and ko = 'KO:霜语者'", Long.class, game)).isZero();
    }

    @Test
    void rejectsOver50() throws Exception {
        long pid = fresh();
        long base = sid(pid, "烈焰之剑");
        String ids = LongStream.range(0, 51).mapToObj(i -> String.valueOf(base + i)).collect(Collectors.joining(","));
        call("{\"segmentIds\":[" + ids + "],\"engine\":\"GPT\"}").andExpect(status().isBadRequest());
        call("{\"segmentIds\":[],\"engine\":\"GPT\"}").andExpect(status().isBadRequest());
        assertThat(fakeLlm.translateCalls).isEmpty();
    }

    @Test
    void rejectsRowsFromTwoGames() throws Exception {
        long p1 = fresh(), p2 = fresh();
        List<Long> ids = new ArrayList<>(List.of(sid(p1, "烈焰之剑"), sid(p2, "烈焰之剑")));
        call("{\"segmentIds\":" + ids + ",\"engine\":\"GPT\"}").andExpect(status().isBadRequest());
        assertThat(fakeLlm.translateCalls).isEmpty();
    }
}
