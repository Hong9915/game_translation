package com.gametrans.translate;

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

class QuickTranslateControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;
    @Autowired MemoryService memory;
    FakeLlmClient fake;
    long projectId;
    long gameId;

    @BeforeEach
    void setUp() throws Exception {
        fake = (FakeLlmClient) llm;
        fake.translateCalls.clear();
        fake.translations.clear();
        projectId = TestProjects.upload(mvc, "한 문장 " + System.nanoTime());
        gameId = JsonPath.parse(mvc.perform(get("/api/projects/{id}", projectId)).andReturn().getResponse().getContentAsString()).read("$.gameId", Long.class);
        TestProjects.normalize(mvc, projectId);
        // 게임 단어장에 霜语者 → 서리속삭임
        mvc.perform(post("/api/games/{id}/glossary", gameId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"SRC\",\"matchMode\":\"PARTIAL\",\"find\":\"霜语者\",\"ko\":\"서리속삭임\"}")).andExpect(status().isOk());
    }

    private static String json(String text) { return "{\"text\":\"" + text + "\"}"; }

    @Test
    void sendsOnlyTheGameTermsThatAppearInTheSentence() throws Exception {
        fake.translations.put("霜语者加入了队伍", "서리속삭임이 파티에 합류했습니다");
        mvc.perform(post("/api/games/{id}/quick-translate", gameId).contentType(MediaType.APPLICATION_JSON).content(json("霜语者加入了队伍")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ko").value("서리속삭임이 파티에 합류했습니다"))
                .andExpect(jsonPath("$.source").value("LLM"))
                .andExpect(jsonPath("$.glossary['霜语者']").value("서리속삭임"))
                .andExpect(jsonPath("$.termMiss").isEmpty())
                .andExpect(jsonPath("$.placeholderOk").value(true));
        assertThat(fake.translateCalls).hasSize(1);
        assertThat(fake.translateCalls.get(0).glossary()).containsEntry("霜语者", "서리속삭임");
        assertThat(fake.translateCalls.get(0).rows()).hasSize(1);

        mvc.perform(post("/api/games/{id}/quick-translate", gameId).contentType(MediaType.APPLICATION_JSON).content(json("商城已开放")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.glossary").isEmpty());
        assertThat(fake.translateCalls.get(1).glossary()).isEmpty();
    }

    @Test
    void flagsAMissingGlossaryTermAndBrokenPlaceholders() throws Exception {
        fake.translations.put("霜语者获得{0}金币", "프로스트가 금화를 얻었습니다");
        mvc.perform(post("/api/games/{id}/quick-translate", gameId).contentType(MediaType.APPLICATION_JSON).content(json("霜语者获得{0}金币")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.termMiss[0]").value("霜语者→서리속삭임"))
                .andExpect(jsonPath("$.placeholderOk").value(false));
    }

    @Test
    void reusesAnExactMemorySentenceWithoutCallingGpt() throws Exception {
        memory.rememberText(projectId, "商城已开放", "상점이 열렸습니다", MemoryEntry.Source.HUMAN);
        mvc.perform(post("/api/games/{id}/quick-translate", gameId).contentType(MediaType.APPLICATION_JSON).content(json("商城已开放")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ko").value("상점이 열렸습니다"))
                .andExpect(jsonPath("$.source").value("MEMORY"));
        assertThat(fake.translateCalls).isEmpty();
        // 빠른 번역 결과는 메모리에 쌓이지 않는다
        mvc.perform(post("/api/games/{id}/quick-translate", gameId).contentType(MediaType.APPLICATION_JSON).content(json("新的一句话")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/games/{id}", gameId)).andExpect(jsonPath("$.memory").value(1));
    }

    @Test
    void rejectsBlankTextAndUnknownGames() throws Exception {
        mvc.perform(post("/api/games/{id}/quick-translate", gameId).contentType(MediaType.APPLICATION_JSON).content(json("  ")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        mvc.perform(post("/api/games/{id}/quick-translate", 999999).contentType(MediaType.APPLICATION_JSON).content(json("你好")))
                .andExpect(status().isNotFound());
    }
}
