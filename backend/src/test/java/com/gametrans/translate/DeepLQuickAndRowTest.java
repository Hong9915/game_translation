package com.gametrans.translate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeMtClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.deepl.MtClient;
import com.gametrans.project.ProjectRepository;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@TestPropertySource(properties = "app.translate.engine=deepl")
class DeepLQuickAndRowTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MtClient mt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectRepository projects;
    FakeMtClient fake;

    @BeforeEach
    void setUp() { fake = (FakeMtClient) mt; fake.reset(); jdbc.update("delete from deepl_cache"); }

    private long gameOf(long pid) { return projects.findById(pid).orElseThrow().getGame().getId(); }

    @Test
    void quickTranslateUsesDeepLAndThenTheCache() throws Exception {
        long game = gameOf(TestProjects.upload(mvc, TestProjects.freshGame()));
        fake.answers.put("霜语者加入了队伍", "서리속삭임이 합류했다");
        String body = "{\"text\":\"霜语者加入了队伍\",\"basis\":\"CN_ONLY\"}";
        mvc.perform(post("/api/games/{id}/quick-translate", game).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ko").value("서리속삭임이 합류했다")).andExpect(jsonPath("$.source").value("DEEPL"));
        mvc.perform(post("/api/games/{id}/quick-translate", game).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.source").value("CACHE"));
        assertThat(fake.sentTexts()).isEqualTo(1);
    }

    @Test
    void quickTranslateSaysTheKeyIsMissing() throws Exception {
        long game = gameOf(TestProjects.upload(mvc, TestProjects.freshGame()));
        fake.configured = false;
        mvc.perform(post("/api/games/{id}/quick-translate", game).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"新的句子\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("DeepL 키가 없습니다. DEEPL_AUTH_KEY를 설정하세요."));
    }

    @Test
    void rowTranslateWritesADeepLRevisionInTheProjectsLanguageAndIgnoresInstructions() throws Exception {
        long pid = TestProjects.upload(mvc, TestProjects.freshGame());
        TestProjects.normalize(mvc, pid);
        projects.updateTranslateSourceLang(pid, "EN");
        Map<String, Object> seg = jdbc.queryForMap("select id from segment where project_id = ? and src_text = '烈焰之剑'", pid);
        long sid = ((Number) seg.get("id")).longValue();
        fake.answers.put("Blazing Sword", "타오르는 검");
        mvc.perform(post("/api/segments/{id}/translate", sid).contentType(MediaType.APPLICATION_JSON).content("{\"instructions\":\"짧게\"}"))
                .andExpect(status().isOk());
        assertThat(fake.sourceLangs).containsExactly("EN");
        Map<String, Object> row = jdbc.queryForMap("""
                select s.latest_ko, (select r.author from translation_revision r where r.segment_id = s.id order by r.id desc limit 1) as author
                from segment s where s.id = ?""", sid);
        assertThat(row.get("latest_ko")).isEqualTo("타오르는 검");
        assertThat(row.get("author")).isEqualTo("DEEPL");
    }

    @Test
    void rowTranslateFlagsATagProblem() throws Exception {
        long pid = TestProjects.upload(mvc, TestProjects.freshGame());
        TestProjects.normalize(mvc, pid);
        Map<String, Object> seg = jdbc.queryForMap("select id from segment where project_id = ? and src_text like '使用后%'", pid);
        long sid = ((Number) seg.get("id")).longValue();
        fake.answers.put("使用后恢复<x i=\"0\"/>点生命值，冷却<x i=\"1\"/>秒。", "사용 후 회복");
        mvc.perform(post("/api/segments/{id}/translate", sid).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        Map<String, Object> row = jdbc.queryForMap("select needs_check, note from segment where id = ?", sid);
        assertThat(row.get("needs_check")).isEqualTo(true);
        assertThat((String) row.get("note")).contains("DeepL: 태그 어긋남");
    }

    @Test
    void quickTranslateExplainsADeepLFailure() throws Exception {
        long game = gameOf(TestProjects.upload(mvc, TestProjects.freshGame()));
        fake.failNext = new com.gametrans.deepl.MtException("DeepL 번역 요청 실패: 429 Too Many Requests");
        mvc.perform(post("/api/games/{id}/quick-translate", game).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"新的句子二\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("DeepL 번역 요청 실패: 429 Too Many Requests"));
    }
}
