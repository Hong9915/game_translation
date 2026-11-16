package com.gametrans.game;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gametrans.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class GameMemoTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    private long createGame() throws Exception {
        String body = mvc.perform(post("/api/games").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"메모 게임 " + System.nanoTime() + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body).read("$.id", Long.class);
    }

    private ResultActions save(long gameId, String content, long baseVersion) throws Exception {
        return mvc.perform(put("/api/games/{id}/memo", gameId).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("content", content, "baseVersion", baseVersion))));
    }

    @Test
    void emptyUntilFirstSave() throws Exception {
        mvc.perform(get("/api/games/{id}/memo", createGame())).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.content").doesNotExist());
    }

    @Test
    void saveRaisesVersionAndKeepsContent() throws Exception {
        long id = createGame();
        save(id, "첫 메모", 0).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        save(id, "고친 메모", 1).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/games/{id}/memo", id))
                .andExpect(jsonPath("$.content").value("고친 메모"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void staleSaveIsRejectedAndChangesNothing() throws Exception {
        long id = createGame();
        save(id, "내가 쓴 것", 0).andExpect(status().isOk());
        // 아직 없는 줄 알고(0) 저장한 두 번째 사람, 옛 버전으로 저장한 사람 모두 거절
        save(id, "뒤늦은 첫 저장", 0).andExpect(status().isConflict()).andExpect(jsonPath("$.message").exists());
        save(id, "다음 저장", 1).andExpect(status().isOk());
        save(id, "옛 버전 저장", 1).andExpect(status().isConflict());
        mvc.perform(get("/api/games/{id}/memo", id)).andExpect(jsonPath("$.content").value("다음 저장"));
    }

    @Test
    void savingRecordsReviewerName() throws Exception {
        long id = createGame();
        String name = "메모 작성자 " + System.nanoTime();
        String reviewer = mvc.perform(post("/api/reviewers").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andReturn().getResponse().getContentAsString();
        long reviewerId = JsonPath.parse(reviewer).read("$.id", Long.class);
        mvc.perform(put("/api/games/{id}/memo", id).header("X-Reviewer-Id", reviewerId).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("content", "x", "baseVersion", 0))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updatedByName").value(name));
    }

    @Test
    void tooLongOrMissingContentIsBadRequest() throws Exception {
        long id = createGame();
        save(id, "가".repeat(GameMemoService.MAX_CONTENT_CHARS + 1), 0).andExpect(status().isBadRequest());
        mvc.perform(put("/api/games/{id}/memo", id).contentType(MediaType.APPLICATION_JSON).content("{\"baseVersion\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownGameIs404() throws Exception {
        mvc.perform(get("/api/games/{id}/memo", 999_999_999L)).andExpect(status().isNotFound());
        save(999_999_999L, "x", 0).andExpect(status().isNotFound());
    }

    @Test
    void deletingGameDeletesMemo() throws Exception {
        long id = createGame();
        save(id, "지워질 메모", 0).andExpect(status().isOk());
        mvc.perform(delete("/api/games/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/games/{id}/memo", id)).andExpect(status().isNotFound());
    }
}
