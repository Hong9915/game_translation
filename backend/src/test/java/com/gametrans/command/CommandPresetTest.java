package com.gametrans.command;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 명령 저장(설계 2026-10-08 §4): 게임 단위로 이름 붙인 작업 목록을 모두가 같이 쓴다. */
class CommandPresetTest extends IntegrationTest {
    static final Map<String, Object> DASH = Map.of("kind", "REPLACE", "steps", List.of(Map.of("find", " - ", "replace", "-", "regex", false)));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private ResultActions create(long pid, String name, Object ops) throws Exception {
        return mvc.perform(post("/api/review/{id}/command-presets", pid).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", name, "ops", ops))));
    }

    @Test
    void presetsAreSharedWithinAGameAndValidated() throws Exception {
        String game = TestProjects.freshGame();
        long p1 = TestProjects.uploadVariants(mvc, game);
        long p2 = TestProjects.uploadVariants(mvc, game);
        long other = TestProjects.uploadVariants(mvc, TestProjects.freshGame());

        create(p1, "대시 공백 지우기", List.of(DASH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("대시 공백 지우기"))
                .andExpect(jsonPath("$.ops[0].kind").value("REPLACE"));
        create(p1, "조사 맞추기", List.of(Map.of("kind", "JOSA"))).andExpect(status().isOk());

        mvc.perform(get("/api/review/{id}/command-presets", p2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("대시 공백 지우기"));
        mvc.perform(get("/api/review/{id}/command-presets", other)).andExpect(jsonPath("$.length()").value(0));

        create(p2, "대시 공백 지우기", List.of(DASH)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("같은 이름의 명령이 있습니다: 대시 공백 지우기"));
        create(p1, " ", List.of(DASH)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("이름을 적어 주세요."));
        create(p1, "x", List.of(Map.of("kind", "NOPE"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("모르는 작업입니다: NOPE"));
    }

    @Test
    void deleteOnlyWithinTheGame() throws Exception {
        long p1 = TestProjects.uploadVariants(mvc, TestProjects.freshGame());
        long other = TestProjects.uploadVariants(mvc, TestProjects.freshGame());
        String body = create(p1, "지울 것", List.of(DASH)).andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).path("id").asLong();
        mvc.perform(delete("/api/review/{id}/command-presets/{pid}", other, id)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/review/{id}/command-presets/{pid}", p1, id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/review/{id}/command-presets", p1)).andExpect(jsonPath("$.length()").value(0));
    }
}
