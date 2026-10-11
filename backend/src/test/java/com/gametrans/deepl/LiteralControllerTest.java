package com.gametrans.deepl;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class LiteralControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired LiteralCacheRepository cache;

    @Test
    void withoutADeepLKeyItAnswersAnEmptyMap() throws Exception {
        mvc.perform(post("/api/glossary/literal").contentType(MediaType.APPLICATION_JSON).content("{\"texts\":[\"随机\"]}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));
    }

    @Test
    void storedLiteralsComeBackFromTheDatabase() throws Exception {
        cache.save(new LiteralCache("陷阱测试", "함정 시험"));
        mvc.perform(post("/api/glossary/literal").contentType(MediaType.APPLICATION_JSON).content("{\"texts\":[\"陷阱测试\",\"随机\"]}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"陷阱测试\":\"함정 시험\"}", true));
    }

    @Test
    void rejectsTooManyTextsAtOnce() throws Exception {
        StringBuilder sb = new StringBuilder("{\"texts\":[");
        for (int i = 0; i <= LiteralController.MAX_TEXTS; i++) sb.append(i > 0 ? "," : "").append("\"词").append(i).append("\"");
        mvc.perform(post("/api/glossary/literal").contentType(MediaType.APPLICATION_JSON).content(sb.append("]}").toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("한 번에 " + LiteralController.MAX_TEXTS + "개까지 물을 수 있습니다."));
    }
}
