package com.gametrans.project;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.TestWorkbooks;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

class ProjectControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;

    @Test
    void uploadCreatesProjectWithSheetAndRowCounts() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "ItemText_v3.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", TestWorkbooks.sample());
        mvc.perform(multipart("/api/projects").file(file).param("gameName", "드래곤 레이드"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("ItemText_v3.xlsx"))
                .andExpect(jsonPath("$.gameName").value("드래곤 레이드"))
                .andExpect(jsonPath("$.gameId").isNumber())
                .andExpect(jsonPath("$.sheetCount").value(2))
                .andExpect(jsonPath("$.rowCount").value(9))
                .andExpect(jsonPath("$.currentStep").value(1));

        mvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileName").value("ItemText_v3.xlsx"));
    }

    @Test
    void uploadAcceptsCsvAsSingleSheetWorkbook() throws Exception {
        String csv = "\uFEFFID,CN,EN\r\n1,\"你好，世界\",Hello\r\n2,再见,Bye\r\n";
        MockMultipartFile file = new MockMultipartFile("file", "words.csv", "text/csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/projects").file(file).param("gameName", "테스트 게임"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("words.csv"))
                .andExpect(jsonPath("$.sheetCount").value(1))
                .andExpect(jsonPath("$.rowCount").value(3));
        long id = projects.findAllByOrderByUploadedAtDesc().get(0).getId();
        mvc.perform(get("/api/projects/{id}/sheets", id).param("headerRow", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sheets[0].name").value("words"))
                .andExpect(jsonPath("$.sheets[0].headers[1].text").value("CN"))
                .andExpect(jsonPath("$.sheets[0].guess.src").value("B"));
    }

    @Test
    void deleteRemovesProjectRowsAndStoredFiles() throws Exception {
        long id = com.gametrans.TestProjects.upload(mvc);
        com.gametrans.TestProjects.normalize(mvc, id);
        String storagePath = projects.findById(id).orElseThrow().getStoragePath();
        java.nio.file.Path dir = java.nio.file.Path.of("build/test-data").resolve(storagePath).getParent();
        assertThat(dir).isDirectory();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/projects/{id}", id))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/projects/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/{id}/normalize", id)).andExpect(status().isNotFound());
        assertThat(dir).doesNotExist();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/projects/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsUnsupportedExtension() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", "hi".getBytes());
        mvc.perform(multipart("/api/projects").file(file).param("gameName", "테스트 게임"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void patchUpdatesGameNameAndStep() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.xlsx", "", TestWorkbooks.sample());
        String body = mvc.perform(multipart("/api/projects").file(file).param("gameName", "테스트 게임")).andReturn().getResponse().getContentAsString();
        long id = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);

        mvc.perform(patch("/api/projects/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gameName\":\"스타 아레나\",\"currentStep\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameName").value("스타 아레나"))
                .andExpect(jsonPath("$.currentStep").value(3));

        mvc.perform(get("/api/projects/{id}", 999999)).andExpect(status().isNotFound());
    }

    @Test
    void sanitizesPathTraversalInFileName() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "../../evil.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", TestWorkbooks.sample());
        String body = mvc.perform(multipart("/api/projects").file(file).param("gameName", "테스트 게임"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("evil.xlsx"))
                .andReturn().getResponse().getContentAsString();
        long id = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);

        Project saved = projects.findById(id).orElseThrow();
        assertThat(saved.getFileName()).isEqualTo("evil.xlsx");
        assertThat(saved.getStoragePath()).matches("projects/[0-9a-fA-F-]+/evil\\.xlsx");
        assertThat(saved.getStoragePath()).doesNotContain("..");
    }

    /** 검수 전용으로 올린 파일은 정규화만 끝나면 단계가 검수(5)로 올라간다 — 용어·번역 단계를 거치지 않는다. */
    @Test
    void reviewOnlyUploadJumpsToReviewAfterNormalize() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "Skill_DB1.xlsx", "", TestWorkbooks.withExistingKo());
        String body = mvc.perform(multipart("/api/projects").file(file).param("gameName", "검수 전용 " + System.nanoTime()).param("reviewOnly", "true"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewOnly").value(true))
                .andExpect(jsonPath("$.currentStep").value(1))
                .andReturn().getResponse().getContentAsString();
        long id = JsonPath.parse(body).read("$.id", Long.class);
        mvc.perform(put("/api/projects/{id}/normalize", id).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE)).andExpect(status().isOk());
        mvc.perform(get("/api/projects/{id}", id)).andExpect(jsonPath("$.currentStep").value(5)).andExpect(jsonPath("$.reviewOnly").value(true));

        // 보통 파일은 그대로 용어 단계(3)로 간다
        MockMultipartFile plain = new MockMultipartFile("file", "Skill_DB1.xlsx", "", TestWorkbooks.withExistingKo());
        long other = JsonPath.parse(mvc.perform(multipart("/api/projects").file(plain).param("gameName", "보통 " + System.nanoTime()))
                .andExpect(jsonPath("$.reviewOnly").value(false)).andReturn().getResponse().getContentAsString()).read("$.id", Long.class);
        mvc.perform(put("/api/projects/{id}/normalize", other).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE)).andExpect(status().isOk());
        mvc.perform(get("/api/projects/{id}", other)).andExpect(jsonPath("$.currentStep").value(3));
    }
}
