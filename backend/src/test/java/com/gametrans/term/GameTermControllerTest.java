package com.gametrans.term;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.llm.LlmClient;
import com.gametrans.memory.MemoryEntry;
import com.gametrans.memory.MemoryService;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class GameTermControllerTest extends IntegrationTest {
    static final String SENTENCE = "一把附有火焰之力的长剑，攻击时有概率造成灼烧。";

    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;
    @Autowired MemoryService memory;
    String game;
    long gameId;

    @BeforeEach
    void setUp() {
        FakeLlmClient fake = (FakeLlmClient) llm;
        fake.translations.clear();
        fake.termsToReturn = List.of(new LlmClient.TermExtraction("霜语者", "CHARACTER"), new LlmClient.TermExtraction("长剑", "ITEM"));
        fake.translations.put(SENTENCE, "화염의 힘이 깃든 장검. 공격 시 확률적으로 화상을 입힙니다.");
        game = "용어집 수정 " + System.nanoTime();
    }

    private String body(String url, Object... vars) throws Exception {
        return mvc.perform(get(url, vars)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void confirm(long pid, String src, String ko) throws Exception {
        List<?> ids = JsonPath.parse(body("/api/projects/{id}/terms", pid)).read("$.terms[?(@.srcText=='" + src + "')].id", List.class);
        mvc.perform(patch("/api/terms/{id}", ((Number) ids.get(0)).longValue()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmedKo\":\"" + ko + "\",\"confirmed\":true}")).andExpect(status().isOk());
    }

    /** 파일 하나를 올려 용어를 확정하고 번역까지 돌린다. */
    private long translatedProject(boolean variant) throws Exception {
        long pid = variant ? TestProjects.uploadVariant(mvc, game) : TestProjects.upload(mvc, game);
        gameId = JsonPath.parse(body("/api/projects/{id}", pid)).read("$.gameId", Long.class);
        TestProjects.normalize(mvc, pid);
        mvc.perform(post("/api/projects/{id}/terms/extract", pid)).andExpect(status().isOk());
        return pid;
    }

    private long gameTermId(String src) throws Exception {
        List<?> ids = JsonPath.parse(body("/api/games/{id}/terms", gameId)).read("$[?(@.srcText=='" + src + "')].id", List.class);
        return ((Number) ids.get(0)).longValue();
    }

    @Test
    void editingAGameTermRewritesEveryFileTheSentencesAndTheMemory() throws Exception {
        long a = translatedProject(false);
        confirm(a, "霜语者", "서리속삭임");
        confirm(a, "长剑", "장검");
        mvc.perform(post("/api/projects/{id}/translate", a).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"));
        memory.rememberText(a, SENTENCE, "화염의 힘이 깃든 장검. 공격 시 확률적으로 화상을 입힙니다.", MemoryEntry.Source.HUMAN);
        long b = translatedProject(true);   // 같은 게임의 두 번째 파일: 霜语者의 번역을 미리 채운 채로 이어받는다

        mvc.perform(patch("/api/games/{id}/terms/{tid}", gameId, gameTermId("霜语者")).contentType(MediaType.APPLICATION_JSON).content("{\"ko\":\"프로스트위스퍼\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.term.ko").value("프로스트위스퍼"))
                .andExpect(jsonPath("$.terms").value(2));
        mvc.perform(get("/api/games/{id}/terms", gameId)).andExpect(jsonPath("$[?(@.srcText=='霜语者')].ko").value("프로스트위스퍼"));
        mvc.perform(get("/api/projects/{id}/terms", a))
                .andExpect(jsonPath("$.terms[?(@.srcText=='霜语者')].confirmedKo").value("프로스트위스퍼"))
                .andExpect(jsonPath("$.terms[?(@.srcText=='霜语者')].confirmed").value(true));
        // 용어 행의 번역도 따라 바뀐다
        mvc.perform(get("/api/projects/{id}/segments", a)).andExpect(jsonPath("$.rows[?(@.srcText=='霜语者')].currentText").value("프로스트위스퍼"));
        // 게임 용어집에서 자동 확정된 파일의 용어도 같이 바뀐다
        mvc.perform(get("/api/projects/{id}/terms", b))
                .andExpect(jsonPath("$.terms[?(@.srcText=='霜语者')].confirmedKo").value("프로스트위스퍼"))
                .andExpect(jsonPath("$.terms[?(@.srcText=='霜语者')].confirmed").value(true));

        // 문장 안의 옛 번역어도 바뀌고, 이력에 GLOSSARY 리비전으로 남는다
        mvc.perform(patch("/api/games/{id}/terms/{tid}", gameId, gameTermId("长剑")).contentType(MediaType.APPLICATION_JSON).content("{\"ko\":\"롱소드\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentences").value(1))
                .andExpect(jsonPath("$.memory").value(1));
        String rows = body("/api/projects/{id}/segments", a);
        List<String> text = JsonPath.parse(rows).read("$.rows[?(@.srcText=='" + SENTENCE + "')].currentText");
        assertThat(text).containsExactly("화염의 힘이 깃든 롱소드. 공격 시 확률적으로 화상을 입힙니다.");
        List<String> authors = JsonPath.parse(rows).read("$.rows[?(@.srcText=='" + SENTENCE + "')].history[-1:].author");
        assertThat(authors).containsExactly("GLOSSARY");
        List<List<String>> miss = JsonPath.parse(rows).read("$.rows[?(@.srcText=='" + SENTENCE + "')].termMiss");
        assertThat(miss.get(0)).isEmpty();
        assertThat(memory.lookupGame(game).exact(SENTENCE)).isEqualTo("화염의 힘이 깃든 롱소드. 공격 시 확률적으로 화상을 입힙니다.");
    }

    @Autowired com.gametrans.glossary.GlossaryEntryRepository glossaryRepo;

    /** 게임 용어를 고쳐 문장을 다시 쓸 때, 그 문장의 용어 누락은 구 용어 표가 아니라 게임의 유효 단어장으로 매긴다. */
    @Test
    void rewrittenSentencesAreCheckedAgainstTheGlossary() throws Exception {
        long a = translatedProject(false);
        confirm(a, "霜语者", "서리속삭임");
        confirm(a, "长剑", "장검");
        mvc.perform(post("/api/projects/{id}/translate", a).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"));
        com.gametrans.glossary.GlossaryEntry g = new com.gametrans.glossary.GlossaryEntry();
        g.setGameId(gameId);
        g.setKind(com.gametrans.glossary.GlossaryEntry.Kind.SRC); g.setMatchMode(com.gametrans.glossary.GlossaryEntry.MatchMode.PARTIAL);
        g.setFind("火焰"); g.setKo("불길"); g.setUpdatedAt(java.time.Instant.now());
        glossaryRepo.save(g);

        mvc.perform(patch("/api/games/{id}/terms/{tid}", gameId, gameTermId("长剑")).contentType(MediaType.APPLICATION_JSON).content("{\"ko\":\"롱소드\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentences").value(1));
        String rows = body("/api/projects/{id}/segments", a);
        List<List<String>> miss = JsonPath.parse(rows).read("$.rows[?(@.srcText=='" + SENTENCE + "')].termMiss");
        assertThat(miss.get(0)).containsExactly("火焰→불길");
    }

    @Test
    void rejectsBlankTextAndTermsOfAnotherGame() throws Exception {
        long a = translatedProject(false);
        confirm(a, "霜语者", "서리속삭임");
        long tid = gameTermId("霜语者");
        mvc.perform(patch("/api/games/{id}/terms/{tid}", gameId, tid).contentType(MediaType.APPLICATION_JSON).content("{\"ko\":\"  \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        long other = JsonPath.parse(mvc.perform(post("/api/games").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + game + " 다른\"}"))
                .andReturn().getResponse().getContentAsString()).read("$.id", Long.class);
        mvc.perform(patch("/api/games/{id}/terms/{tid}", other, tid).contentType(MediaType.APPLICATION_JSON).content("{\"ko\":\"x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/games/{id}/terms", gameId)).andExpect(jsonPath("$[0].ko").value("서리속삭임"));
    }

    /** 긴 용어의 번역에 그 안의 짧은 용어 번역이 빠져 있으면 conflicts로 알려 주고, 고치면 사라진다. */
    @Test
    void flagsCompoundTermsThatDisagreeWithTheirParts() throws Exception {
        ((FakeLlmClient) llm).termsToReturn = List.of(new LlmClient.TermExtraction("烈焰之剑", "ITEM"), new LlmClient.TermExtraction("烈焰", "COMMON"));
        long a = translatedProject(false);
        confirm(a, "烈焰", "화염");
        confirm(a, "烈焰之剑", "불꽃의 검");
        mvc.perform(get("/api/games/{id}/terms", gameId))
                .andExpect(jsonPath("$[?(@.srcText=='烈焰之剑')].conflicts[0]").value("烈焰→화염"))
                .andExpect(jsonPath("$[?(@.srcText=='烈焰')].conflicts[0]").doesNotExist());
        mvc.perform(patch("/api/games/{id}/terms/{tid}", gameId, gameTermId("烈焰之剑")).contentType(MediaType.APPLICATION_JSON).content("{\"ko\":\"화염의 검\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/games/{id}/terms", gameId)).andExpect(jsonPath("$[?(@.srcText=='烈焰之剑')].conflicts[0]").doesNotExist());
    }

    /** 숫자만 다른 변형 용어는 목록에서 기본 단어(stem)를 알려 주고, 기본 단어 자신은 stem이 없다. */
    @Test
    void listsTheStemOfNumberedVariants() throws Exception {
        long a = translatedProject(false);
        confirm(a, "霜语者", "서리속삭임");
        mvc.perform(post("/api/projects/{id}/terms", a).contentType(MediaType.APPLICATION_JSON)
                .content("{\"srcText\":\"11霜语者\",\"type\":\"CHARACTER\",\"confirmedKo\":\"11성 서리속삭임\"}")).andExpect(status().isCreated());
        mvc.perform(get("/api/games/{id}/terms", gameId))
                .andExpect(jsonPath("$[?(@.srcText=='11霜语者')].stem").value("霜语者"))
                .andExpect(jsonPath("$[?(@.srcText=='霜语者')].stem").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
    }

    /** 같은 낱말이 든 용어를 한 번에 바꾼다: 긴 용어부터 고쳐 문장·파일 용어·메모리까지 같이 바뀌고, 원문 조건에 안 맞는 용어는 그대로. */
    @Test
    void replacesAWordAcrossEveryMatchingGameTerm() throws Exception {
        FakeLlmClient fake = (FakeLlmClient) llm;
        fake.termsToReturn = List.of(new LlmClient.TermExtraction("长剑", "ITEM"), new LlmClient.TermExtraction("火焰之力的长剑", "ITEM"),
                new LlmClient.TermExtraction("烈焰之剑", "ITEM"));
        fake.translations.put(SENTENCE, "화염의 장검. 공격 시 확률적으로 화상을 입힙니다.");
        long a = translatedProject(false);
        confirm(a, "长剑", "장검");
        confirm(a, "火焰之力的长剑", "화염의 장검");
        confirm(a, "烈焰之剑", "불꽃 장검");   // 원문에 长剑이 없으므로 "장검"이 들어 있어도 건드리지 않는다
        mvc.perform(post("/api/projects/{id}/translate", a).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"));
        memory.rememberText(a, SENTENCE, "화염의 장검. 공격 시 확률적으로 화상을 입힙니다.", MemoryEntry.Source.HUMAN);

        mvc.perform(post("/api/games/{id}/terms/replace", gameId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"src\":\"长剑\",\"from\":\"장검\",\"to\":\"롱소드\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(2))
                .andExpect(jsonPath("$.terms").value(2))
                .andExpect(jsonPath("$.sentences").value(1))
                .andExpect(jsonPath("$.memory").value(1));
        mvc.perform(get("/api/games/{id}/terms", gameId))
                .andExpect(jsonPath("$[?(@.srcText=='长剑')].ko").value("롱소드"))
                .andExpect(jsonPath("$[?(@.srcText=='火焰之力的长剑')].ko").value("화염의 롱소드"))
                .andExpect(jsonPath("$[?(@.srcText=='烈焰之剑')].ko").value("불꽃 장검"));
        String rows = body("/api/projects/{id}/segments", a);
        List<String> text = JsonPath.parse(rows).read("$.rows[?(@.srcText=='" + SENTENCE + "')].currentText");
        assertThat(text).containsExactly("화염의 롱소드. 공격 시 확률적으로 화상을 입힙니다.");
        List<List<String>> miss = JsonPath.parse(rows).read("$.rows[?(@.srcText=='" + SENTENCE + "')].termMiss");
        assertThat(miss.get(0)).isEmpty();
        assertThat(memory.lookupGame(game).exact(SENTENCE)).isEqualTo("화염의 롱소드. 공격 시 확률적으로 화상을 입힙니다.");

        mvc.perform(post("/api/games/{id}/terms/replace", gameId).contentType(MediaType.APPLICATION_JSON).content("{\"from\":\" \",\"to\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }
}
