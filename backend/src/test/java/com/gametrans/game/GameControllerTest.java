package com.gametrans.game;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.TestWorkbooks;
import com.gametrans.llm.LlmClient;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

class GameControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;

    @BeforeEach
    void setUp() {
        ((FakeLlmClient) llm).termsToReturn = java.util.List.of(new LlmClient.TermExtraction("霜语者", "CHARACTER"));
    }

    private long termId(long pid, String src) throws Exception {
        String body = mvc.perform(get("/api/projects/{id}/terms", pid)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        java.util.List<?> ids = JsonPath.parse(body).read("$.terms[?(@.srcText=='" + src + "')].id", java.util.List.class);
        return ((Number) ids.get(0)).longValue();
    }

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
    void renameCarriesSharedTermsAndMemoryToTheNewName() throws Exception {
        String name = "이름 바꾸기 " + System.nanoTime();
        long a = TestProjects.upload(mvc, name);
        long id = JsonPath.parse(mvc.perform(get("/api/projects/{id}", a)).andReturn().getResponse().getContentAsString()).read("$.gameId", Long.class);
        TestProjects.normalize(mvc, a);
        mvc.perform(post("/api/projects/{id}/terms/extract", a)).andExpect(status().isOk());
        mvc.perform(patch("/api/terms/{id}", termId(a, "霜语者")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmedKo\":\"서리속삭임\",\"confirmed\":true}")).andExpect(status().isOk());
        mvc.perform(get("/api/games/{id}", id)).andExpect(jsonPath("$.terms").value(1));

        mvc.perform(patch("/api/games/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + " 새이름\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/games/{id}", id)).andExpect(jsonPath("$.name").value(name + " 새이름")).andExpect(jsonPath("$.terms").value(1));
        mvc.perform(get("/api/projects/{id}", a)).andExpect(jsonPath("$.gameName").value(name + " 새이름"));

        // 새 이름의 게임에 올린 파일이 확정 용어를 이어받는다
        long b = TestProjects.uploadVariant(mvc, name + " 새이름");
        TestProjects.normalize(mvc, b);
        mvc.perform(post("/api/projects/{id}/terms/extract", b)).andExpect(status().isOk());
        mvc.perform(get("/api/projects/{id}/terms", b))
                .andExpect(jsonPath("$.terms[?(@.srcText=='霜语者')].confirmedKo").value("서리속삭임"));
    }

    @Test
    void listsTheGameGlossaryWithConfirmedTermsOnly() throws Exception {
        String name = "용어집 보기 " + System.nanoTime();
        long a = TestProjects.upload(mvc, name);
        long id = JsonPath.parse(mvc.perform(get("/api/projects/{id}", a)).andReturn().getResponse().getContentAsString()).read("$.gameId", Long.class);
        mvc.perform(get("/api/games/{id}/terms", id)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        TestProjects.normalize(mvc, a);
        mvc.perform(post("/api/projects/{id}/terms/extract", a)).andExpect(status().isOk());
        mvc.perform(patch("/api/terms/{id}", termId(a, "霜语者")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmedKo\":\"서리속삭임\",\"confirmed\":true}")).andExpect(status().isOk());

        mvc.perform(get("/api/games/{id}/terms", id))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].srcText").value("霜语者"))
                .andExpect(jsonPath("$[0].type").value("CHARACTER"))
                .andExpect(jsonPath("$[0].ko").value("서리속삭임"));
        // 다른 게임의 용어는 섞이지 않는다
        mvc.perform(get("/api/games/{id}/terms", createGame("다른 용어집 " + System.nanoTime()))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/games/{id}/terms", 999999)).andExpect(status().isNotFound());
    }

    @Test
    void deletingAGameRemovesItsProjectsFilesAndSharedAssets() throws Exception {
        String name = "삭제 게임 " + System.nanoTime();
        long a = TestProjects.upload(mvc, name);
        long id = JsonPath.parse(mvc.perform(get("/api/projects/{id}", a)).andReturn().getResponse().getContentAsString()).read("$.gameId", Long.class);
        TestProjects.normalize(mvc, a);
        mvc.perform(post("/api/projects/{id}/terms/extract", a)).andExpect(status().isOk());
        mvc.perform(patch("/api/terms/{id}", termId(a, "霜语者")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmedKo\":\"서리속삭임\",\"confirmed\":true}")).andExpect(status().isOk());

        mvc.perform(delete("/api/games/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/games/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/{id}", a)).andExpect(status().isNotFound());
        mvc.perform(get("/api/games")).andExpect(jsonPath("$[?(@.name=='" + name + "')]").isEmpty());

        // 같은 이름으로 다시 만들면 예전 용어가 따라오지 않는다
        long c = TestProjects.upload(mvc, name);
        TestProjects.normalize(mvc, c);
        mvc.perform(post("/api/projects/{id}/terms/extract", c)).andExpect(status().isOk());
        mvc.perform(get("/api/projects/{id}/terms", c))
                .andExpect(jsonPath("$.terms[0].srcText").value("霜语者"))
                .andExpect(jsonPath("$.terms[0].confirmedKo").doesNotExist());
    }
}
