package com.gametrans.game;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.TestWorkbooks;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

class GameControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;

    private long createGame(String name) throws Exception {
        String body = mvc.perform(post("/api/games").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.projects").value(0))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body).read("$.id", Long.class);
    }

    @Test
    void emptyGameCanBeCreatedListedAndRenamed() throws Exception {
        String name = "빈 게임 " + System.nanoTime();
        long id = createGame(name);
        mvc.perform(get("/api/games")).andExpect(jsonPath("$[?(@.id==" + id + ")].name").value(name));
        mvc.perform(get("/api/games/{id}", id)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value(name));

        mvc.perform(post("/api/games").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        mvc.perform(post("/api/games").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(patch("/api/games/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + " 2\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value(name + " 2"));
        mvc.perform(get("/api/games/{id}", 999999)).andExpect(status().isNotFound());
    }

    @Test
    void uploadRequiresAGameAndListsProjectsPerGame() throws Exception {
        long id = createGame("업로드 게임 " + System.nanoTime());
        MockMultipartFile file = new MockMultipartFile("file", "a.xlsx", "", TestWorkbooks.sample());
        mvc.perform(multipart("/api/projects").file(file)).andExpect(status().isBadRequest());

        String body = mvc.perform(multipart("/api/projects").file(file).param("gameId", String.valueOf(id)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gameId").value(id))
                .andReturn().getResponse().getContentAsString();
        long projectId = JsonPath.parse(body).read("$.id", Long.class);

        mvc.perform(get("/api/games/{id}", id))
                .andExpect(jsonPath("$.projects").value(1))
                .andExpect(jsonPath("$.lastUploadedAt").isNotEmpty());
        mvc.perform(get("/api/projects").param("gameId", String.valueOf(id)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(projectId));

        long other = createGame("다른 게임 " + System.nanoTime());
        mvc.perform(get("/api/projects").param("gameId", String.valueOf(other))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(multipart("/api/projects").file(file).param("gameId", "999999")).andExpect(status().isNotFound());
    }

    @Test
    void uploadByGameNameCreatesTheGameWhenMissing() throws Exception {
        String name = "이름으로 " + System.nanoTime();
        MockMultipartFile file = new MockMultipartFile("file", "a.xlsx", "", TestWorkbooks.sample());
        mvc.perform(multipart("/api/projects").file(file).param("gameName", name))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gameName").value(name))
                .andExpect(jsonPath("$.gameId").isNumber());
        mvc.perform(get("/api/games")).andExpect(jsonPath("$[?(@.name=='" + name + "')].projects").value(1));
    }

    @Test
    void renameCarriesTheNameToItsProjects() throws Exception {
        String name = "이름 바꾸기 " + System.nanoTime();
        long a = TestProjects.upload(mvc, name);
        long id = JsonPath.parse(mvc.perform(get("/api/projects/{id}", a)).andReturn().getResponse().getContentAsString()).read("$.gameId", Long.class);
        mvc.perform(patch("/api/games/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + " 새이름\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/games/{id}", id)).andExpect(jsonPath("$.name").value(name + " 새이름"));
        mvc.perform(get("/api/projects/{id}", a)).andExpect(jsonPath("$.gameName").value(name + " 새이름"));
    }

    @Test
    void deletingAGameRemovesItsProjectsAndFiles() throws Exception {
        String name = "삭제 게임 " + System.nanoTime();
        long a = TestProjects.upload(mvc, name);
        long id = JsonPath.parse(mvc.perform(get("/api/projects/{id}", a)).andReturn().getResponse().getContentAsString()).read("$.gameId", Long.class);
        TestProjects.normalize(mvc, a);

        mvc.perform(delete("/api/games/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/games/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/{id}", a)).andExpect(status().isNotFound());
        mvc.perform(get("/api/games")).andExpect(jsonPath("$[?(@.name=='" + name + "')]").isEmpty());
    }

    @Test
    void deepLInstructionsStartFromTheDefaultAndAreCheckedAgainstDeepLLimits() throws Exception {
        long id = createGame("지시문 " + System.nanoTime());
        mvc.perform(get("/api/games/{id}", id)).andExpect(jsonPath("$.deeplInstructions").value(com.gametrans.game.DeepLInstructions.DEFAULT));

        mvc.perform(put("/api/games/{id}/deepl-instructions", id).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\" 짧게 \\n\\n 게임 용어로\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deeplInstructions").value("짧게\n게임 용어로"));
        String eleven = "a\\n".repeat(11);
        mvc.perform(put("/api/games/{id}/deepl-instructions", id).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + eleven + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/games/{id}/deepl-instructions", id).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + "가".repeat(301) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/games/{id}/deepl-instructions", id).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deeplInstructions").value(""));
    }
}
