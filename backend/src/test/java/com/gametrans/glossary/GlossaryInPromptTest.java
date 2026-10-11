package com.gametrans.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.llm.LlmClient;
import com.gametrans.memory.GameTerm;
import com.gametrans.memory.GameTermRepository;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.Project;
import com.gametrans.project.ProjectRepository;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 행 번역·빠른 번역 프롬프트에는 유효 단어장 가운데 그 원문에 실제로 걸리는 항목만 들어가고, ko가 빈 항목은 메모만 지시문으로 들어간다. */
class GlossaryInPromptTest extends IntegrationTest {
    static final String SRC = "正义裁决";

    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired SegmentRepository segments;
    @Autowired RevisionService revisions;
    @Autowired GameTermRepository gameTerms;
    @Autowired LlmClient llm;
    FakeLlmClient fakeLlm;

    Long pid;
    Long segmentId;

    @BeforeEach
    void resetFake() {
        fakeLlm = (FakeLlmClient) llm;
        fakeLlm.translateCalls.clear();
        fakeLlm.translations.clear();
        fakeLlm.dropOnce.clear();
        fakeLlm.breakPlaceholdersOnce.clear();
    }

    /** 기존 한국어가 있는 Skill 시트 프로젝트를 새 게임에 만든다. pid와 正义裁决 행의 segmentId를 채우고 게임 id를 돌려준다. */
    private Long gameOfNewProject() throws Exception { return gameOf(TestProjects.uploadExistingKo(mvc, TestProjects.freshGame())); }

    private Long gameOf(long projectId) throws Exception {
        pid = projectId;
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE)).andExpect(status().isOk());
        segmentId = judgementRow().getId();
        return projects.findById(pid).orElseThrow().getGame().getId();
    }

    private Segment judgementRow() {
        return segments.findByProjectIdAndSheetNameAndSkippedFalseOrderByRowNoAscPairIndexAsc(pid, "Skill").stream()
                .filter(s -> SRC.equals(s.getSrcText())).findFirst().orElseThrow();
    }

    private void addEntry(Long g, String json) throws Exception {
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
    }

    private long reviewer() throws Exception {
        String name = "행번역-" + UUID.randomUUID().toString().substring(0, 6);
        String body = mvc.perform(post("/api/reviewers").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.parse(body).read("$.id")).longValue();
    }

    @Test
    void theRowTranslatePromptCarriesOnlyTheMatchingEntries() throws Exception {
        Long g = gameOfNewProject();
        addEntry(g, "{\"kind\":\"SRC\",\"matchMode\":\"PARTIAL\",\"find\":\"裁决\",\"ko\":\"판결\"}");
        addEntry(g, "{\"kind\":\"SRC\",\"matchMode\":\"PARTIAL\",\"find\":\"红包\",\"ko\":\"행운 봉투\"}");

        mvc.perform(post("/api/segments/{sid}/translate", segmentId)).andExpect(status().isOk());

        assertThat(fakeLlm.lastGlossary()).containsEntry("裁决", "판결").doesNotContainKey("红包");
    }

    @Test
    void memoOnlyEntriesGoInAsNotes() throws Exception {
        Long g = gameOfNewProject();
        addEntry(g, "{\"kind\":\"SRC\",\"matchMode\":\"PARTIAL\",\"find\":\"裁决\",\"note\":\"버프 계열과 헷갈리지 말 것\"}");
        // 원문에 없는 메모는 들어가지 않는다
        addEntry(g, "{\"kind\":\"SRC\",\"matchMode\":\"PARTIAL\",\"find\":\"红包\",\"note\":\"봉투라고 쓰지 말 것\"}");

        mvc.perform(post("/api/segments/{sid}/translate", segmentId)).andExpect(status().isOk());

        assertThat(fakeLlm.lastInstructions()).contains("버프 계열과 헷갈리지 말 것").doesNotContain("봉투라고 쓰지 말 것");
        assertThat(fakeLlm.lastGlossary()).doesNotContainKey("裁决");   // 메모 전용 항목은 용어집 칸에 들어가지 않는다
    }

    @Test
    void rowTranslateStoresAnLlmRevisionAndReturnsTheFreshRow() throws Exception {
        // 한 시트 안에 正义裁决 행이 둘 — 응답의 dupCount가 2여야 한다
        Long g = gameOf(TestProjects.uploadExistingKoSameSourceTwice(mvc, TestProjects.freshGame()));
        addEntry(g, "{\"kind\":\"SRC\",\"matchMode\":\"PARTIAL\",\"find\":\"裁决\",\"ko\":\"판결\"}");
        fakeLlm.translations.put(SRC, "정의의 심판자");   // 판결이 빠진 번역
        long rid = reviewer();

        mvc.perform(post("/api/segments/{sid}/translate", segmentId).header("X-Reviewer-Id", rid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(segmentId))
                .andExpect(jsonPath("$.fix").value("정의의 심판자"))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.termMiss[0]").value("裁决→판결"))
                .andExpect(jsonPath("$.termMiss.length()").value(1))
                .andExpect(jsonPath("$.lastEditor.id").value(rid))
                .andExpect(jsonPath("$.dupCount").value(2))
                .andExpect(jsonPath("$.history[-1:].author").value(Matchers.contains("LLM")));

        TranslationRevision last = revisions.current(segmentId).orElseThrow();
        assertThat(last.getAuthor()).isEqualTo(TranslationRevision.Author.LLM);
        assertThat(last.getText()).isEqualTo("정의의 심판자");
        assertThat(last.getReviewerId()).isEqualTo(rid);
        assertThat(segments.findById(segmentId).orElseThrow().getTermMiss()).isEqualTo("裁决→판결");
    }

    @Test
    void skippedAndTermRowsAreRejected() throws Exception {
        gameOfNewProject();

        Segment other = segments.findByProjectIdAndSheetNameAndSkippedFalseOrderByRowNoAscPairIndexAsc(pid, "Skill").stream()
                .filter(s -> !SRC.equals(s.getSrcText())).findFirst().orElseThrow();
        other.setTerm(true);
        segments.save(other);
        mvc.perform(post("/api/segments/{sid}/translate", other.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("용어")));

        other.setTerm(false);
        other.setSkipped(true);
        segments.save(other);
        mvc.perform(post("/api/segments/{sid}/translate", other.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("제외")));

        assertThat(fakeLlm.translateCalls).isEmpty();
    }

    @Test
    void quickTranslateUsesTheGameGlossaryAndAGameEntryBeatsAnOldTerm() throws Exception {
        Long g = gameOfNewProject();
        Project p = projects.findById(pid).orElseThrow();
        String game = p.getGame().getName();
        // 구 공용 용어: 正义은 단어장에 없으니 그대로 쓰이고, 裁决은 단어장 항목에 밀린다
        for (String[] t : new String[][] {{"正义", "정의"}, {"裁决", "재결"}}) {
            GameTerm gt = new GameTerm();
            gt.setGameName(game); gt.setSrcText(t[0]); gt.setType("OTHER"); gt.setKo(t[1]);
            gameTerms.save(gt);
        }
        addEntry(g, "{\"kind\":\"SRC\",\"matchMode\":\"PARTIAL\",\"find\":\"裁决\",\"ko\":\"판결\"}");
        addEntry(g, "{\"kind\":\"SRC\",\"matchMode\":\"PARTIAL\",\"find\":\"红包\",\"ko\":\"행운 봉투\"}");
        addEntry(g, "{\"kind\":\"SRC\",\"matchMode\":\"PARTIAL\",\"find\":\"正义\",\"note\":\"정의감이 아니라 정의(법)\"}");

        mvc.perform(post("/api/games/{id}/quick-translate", g).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + SRC + "\",\"instructions\":\"짧게\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.glossary['裁决']").value("판결"));

        assertThat(fakeLlm.lastGlossary())
                .containsEntry("裁决", "판결")
                .containsEntry("正义", "정의")
                .doesNotContainKey("红包");
        assertThat(fakeLlm.lastInstructions()).startsWith("짧게").contains("정의감이 아니라 정의(법)");
    }
}
