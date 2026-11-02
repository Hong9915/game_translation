package com.gametrans.translate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeMtClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.deepl.MtClient;
import com.gametrans.deepl.MtException;
import com.gametrans.project.ProjectRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@TestPropertySource(properties = "app.translate.engine=deepl")
class DeepLTranslateFlowTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MtClient mt;
    @Autowired JdbcTemplate jdbc;
    @Autowired RevisionService revisions;
    @Autowired ProjectRepository projects;
    @Autowired com.gametrans.normalize.SegmentRepository segments;
    FakeMtClient fake;

    @BeforeEach
    void setUp() { fake = (FakeMtClient) mt; fake.reset(); jdbc.update("delete from deepl_cache"); }

    private long normalized() throws Exception {
        long pid = TestProjects.upload(mvc, TestProjects.freshGame());
        TestProjects.normalize(mvc, pid);
        return pid;
    }

    private void start(long pid, String body) throws Exception {
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
    }

    private List<Map<String, Object>> rows(long pid) {
        return jdbc.queryForList("""
                select s.id, s.src_text, s.latest_ko, s.needs_check, s.note,
                       (select r.author from translation_revision r where r.segment_id = s.id order by r.id desc limit 1) as author
                from segment s where s.project_id = ? and s.skipped = false and coalesce(s.src_text, '') <> '' order by s.row_no
                """, pid);
    }

    @Test
    void translatesTheEmptyRowsOfTheChosenSheetWithDeepL() throws Exception {
        long pid = normalized();
        start(pid, "{\"sheet\":\"ItemText\",\"sourceLang\":\"ZH\"}");
        List<Map<String, Object>> rows = rows(pid);
        assertThat(rows).isNotEmpty().allSatisfy(r -> {
            assertThat(r.get("author")).isEqualTo("DEEPL");
            assertThat((String) r.get("latest_ko")).doesNotContainPattern("[\\u4e00-\\u9fff]");
        });
        assertThat(rows).anySatisfy(r -> assertThat((String) r.get("latest_ko")).isEqualTo("<color=#FFD700>가가</color> 가가가가"));
        assertThat(fake.sentTexts()).isEqualTo(rows.size());
    }

    @Test
    void anotherSheetIsLeftAlone() throws Exception {
        long pid = normalized();
        start(pid, "{\"sheet\":\"Other\",\"sourceLang\":\"ZH\"}");
        assertThat(fake.calls).isEmpty();
        assertThat(rows(pid)).allSatisfy(r -> assertThat(r.get("author")).isNull());
    }

    @Test
    void flagsRowsWhoseTagsCannotBeKept() throws Exception {
        long pid = normalized();
        fake.answers.put("使用后恢复<x i=\"0\"/>点生命值，冷却<x i=\"1\"/>秒。", "사용 후 회복, 재사용 대기");
        start(pid, "{\"sheet\":\"ItemText\"}");
        Map<String, Object> row = rows(pid).stream().filter(r -> ((String) r.get("src_text")).startsWith("使用后")).findFirst().orElseThrow();
        assertThat(row.get("needs_check")).isEqualTo(true);
        assertThat((String) row.get("note")).contains("DeepL: 태그 어긋남");
    }

    @Test
    void aFailedDeepLCallLeavesItsRowsForTheNextRun() throws Exception {
        long pid = normalized();
        fake.failNext = new MtException("boom");
        start(pid, "{\"sheet\":\"ItemText\"}");
        assertThat(rows(pid)).allSatisfy(r -> assertThat(r.get("author")).isNull());
        start(pid, "{\"sheet\":\"ItemText\"}");
        assertThat(rows(pid)).allSatisfy(r -> assertThat(r.get("author")).isEqualTo("DEEPL"));
    }

    @Test
    void doesNotOverwriteARowSavedWhileDeepLWasWorking() throws Exception {
        long pid = normalized();
        Long first = ((Number) rows(pid).get(0).get("id")).longValue();
        fake.beforeTranslate = () -> revisions.add(first, "사람이 쓴 번역", TranslationRevision.Author.HUMAN);
        start(pid, "{\"sheet\":\"ItemText\"}");
        Map<String, Object> row = rows(pid).stream().filter(r -> ((Number) r.get("id")).longValue() == first).findFirst().orElseThrow();
        assertThat(row.get("latest_ko")).isEqualTo("사람이 쓴 번역");
        assertThat(row.get("author")).isEqualTo("HUMAN");
    }

    @Test
    void remembersTheSourceLanguageOnTheProject() throws Exception {
        long pid = normalized();
        start(pid, "{\"sheet\":\"ItemText\",\"sourceLang\":\"EN\"}");
        assertThat(projects.findById(pid).orElseThrow().getTranslateSourceLang()).isEqualTo("EN");
        assertThat(fake.sourceLangs).contains("EN");
    }

    @Test
    void refusesToStartWithoutADeepLKey() throws Exception {
        long pid = normalized();
        fake.configured = false;
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON).content("{\"sheet\":\"ItemText\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("DeepL 키가 없습니다. DEEPL_AUTH_KEY를 설정하세요."));
    }

    @Test
    void keepsANoteWrittenWhileDeepLWasWorking() throws Exception {
        long pid = normalized();
        Long id = rows(pid).stream().filter(r -> ((String) r.get("src_text")).startsWith("使用后")).map(r -> ((Number) r.get("id")).longValue()).findFirst().orElseThrow();
        fake.answers.put("使用后恢复<x i=\"0\"/>点生命值，冷却<x i=\"1\"/>秒。", "사용 후 회복");
        fake.beforeTranslate = () -> segments.updateNote(id, "검토자 메모");
        start(pid, "{\"sheet\":\"ItemText\"}");
        String note = (String) rows(pid).stream().filter(r -> ((Number) r.get("id")).longValue() == id).findFirst().orElseThrow().get("note");
        assertThat(note).isEqualTo("검토자 메모 · DeepL: 태그 어긋남");
    }
}
