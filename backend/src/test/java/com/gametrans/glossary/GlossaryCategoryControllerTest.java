package com.gametrans.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.llm.LlmClient;
import com.gametrans.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class GlossaryCategoryControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired LlmClient llm;
    FakeLlmClient fake;

    @BeforeEach
    void setUp() {
        fake = (FakeLlmClient) llm;
        fake.categories.clear();
        fake.classifyCalls.clear();
    }

    Long game() throws Exception {
        long pid = TestProjects.uploadVariants(mvc, TestProjects.freshGame());
        return projects.findById(pid).orElseThrow().getGame().getId();
    }

    static final String BODY = """
        {"items":[{"src":"蝎子王","ko":"전갈왕","example":"击败蝎子王"},{"src":"用于","ko":"사용","example":"用于升级"},
                  {"src":"红色","ko":"빨간색","example":"红色宝石"}]}""";

    @Test
    void classifiesOnceAndThenReadsTheSavedCategory() throws Exception {
        Long g = game();
        fake.categories.put("蝎子王", "CHARACTER");
        fake.categories.put("用于", "function");   // 소문자도 받는다
        fake.categories.put("红色", "SHINY");      // 모르는 분류는 버린다
        mvc.perform(post("/api/games/{id}/glossary/categories", g).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['蝎子王']").value("CHARACTER"))
                .andExpect(jsonPath("$['用于']").value("FUNCTION"))
                .andExpect(jsonPath("$['红色']").doesNotExist());
        assertThat(fake.classifyCalls).hasSize(1);
        assertThat(fake.classifyCalls.get(0)).extracting(LlmClient.PhraseInput::ko).containsExactly("전갈왕", "사용", "빨간색");

        fake.categories.put("红色", "COLOR");
        mvc.perform(post("/api/games/{id}/glossary/categories", g).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['蝎子王']").value("CHARACTER"))
                .andExpect(jsonPath("$['红色']").value("COLOR"));
        // 두 번째에는 저장되지 않은 红色만 묻는다
        assertThat(fake.classifyCalls).hasSize(2);
        assertThat(fake.classifyCalls.get(1)).extracting(LlmClient.PhraseInput::src).containsExactly("红色");
    }

    @Test
    void rejectsUnknownGame() throws Exception {
        mvc.perform(post("/api/games/{id}/glossary/categories", 999999L).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void asksBatchesAtTheSameTimeAndKeepsEveryResult() throws Exception {
        Long g = game();
        StringBuilder body = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 250; i++) {
            String src = "词" + i;
            fake.categories.put(src, "NOUN");
            body.append(i == 0 ? "" : ",").append("{\"src\":\"").append(src).append("\",\"ko\":\"낱말\",\"example\":null}");
        }
        body.append("]}");
        String res = mvc.perform(post("/api/games/{id}/glossary/categories", g).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(res).size()).isEqualTo(250);
        assertThat(fake.classifyCalls).hasSize(3);   // 100 + 100 + 50
    }
}
