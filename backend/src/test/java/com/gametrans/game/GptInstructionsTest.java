package com.gametrans.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class GptInstructionsTest extends IntegrationTest {
    @Autowired MockMvc mvc;

    private long createGame() throws Exception {
        String body = mvc.perform(post("/api/games").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"gpt지시 " + System.nanoTime() + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body).read("$.id", Long.class);
    }

    @Test
    void defaultsAndSave() throws Exception {
        long id = createGame();
        mvc.perform(get("/api/games/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.gptTranslateBase").value(org.hamcrest.Matchers.startsWith("너는 중국어 모바일 게임의 글을 한국어로 옮기는 현지화 담당자다.")))
                .andExpect(jsonPath("$.gptPolishBase").value(GptInstructions.POLISH_BASE))
                .andExpect(jsonPath("$.gptTranslateExtra").doesNotExist());

        mvc.perform(put("/api/games/{id}/gpt-instructions", id).contentType(MediaType.APPLICATION_JSON).content("{\"translate\":\"존댓말\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.gptTranslateExtra").value("존댓말"))
                .andExpect(jsonPath("$.gptPolishExtra").doesNotExist());
        mvc.perform(put("/api/games/{id}/gpt-instructions", id).contentType(MediaType.APPLICATION_JSON).content("{\"polish\":\"짧게\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.gptTranslateExtra").value("존댓말")).andExpect(jsonPath("$.gptPolishExtra").value("짧게"));
        mvc.perform(put("/api/games/{id}/gpt-instructions", id).contentType(MediaType.APPLICATION_JSON).content("{\"translate\":\"" + "가".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/games/{id}/gpt-instructions", id).contentType(MediaType.APPLICATION_JSON).content("{\"translate\":\"  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.gptTranslateExtra").doesNotExist()).andExpect(jsonPath("$.gptPolishExtra").value("짧게"));
    }

    @Test
    void fullJoinsExtra() {
        assertEquals("B\n\n추가 지시:\nx", GptInstructions.full("B", " x "));
        assertEquals("B", GptInstructions.full("B", null));
        assertEquals("B", GptInstructions.full("B", "  "));
    }
}
