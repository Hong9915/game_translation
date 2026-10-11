package com.gametrans.normalize;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class NormalizeControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void sheetsReturnsHeadersAndGuesses() throws Exception {
        long id = TestProjects.upload(mvc);
        mvc.perform(get("/api/projects/{id}/sheets", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sheets[0].name").value("ItemText"))
                .andExpect(jsonPath("$.sheets[0].rowCount").value(7))
                .andExpect(jsonPath("$.sheets[0].headers[2].letter").value("C"))
                .andExpect(jsonPath("$.sheets[0].headers[2].text").value("CN_Text"))
                .andExpect(jsonPath("$.sheets[0].guess.src").value("C"))
                .andExpect(jsonPath("$.sheets[0].guess.en").value("D"))
                .andExpect(jsonPath("$.sheets[0].guess.key").value("A"));
    }

    @Test
    void normalizeCreatesSegmentsSkippingEmptySource() throws Exception {
        long id = TestProjects.upload(mvc);
        mvc.perform(put("/api/projects/{id}/normalize", id).contentType(MediaType.APPLICATION_JSON).content(TestProjects.DEFAULT_NORMALIZE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.totalRows").value(6))
                .andExpect(jsonPath("$.stats.normalized").value(5))
                .andExpect(jsonPath("$.stats.skipped").value(1))
                .andExpect(jsonPath("$.stats.withEn").value(4))
                .andExpect(jsonPath("$.preview[0].indexLabel").value("ItemText!2"))
                .andExpect(jsonPath("$.preview[0].keyText").value("IT_10001"))
                .andExpect(jsonPath("$.preview[0].srcText").value("烈焰之剑"))
                .andExpect(jsonPath("$.preview[0].enText").value("Blazing Sword"))
                .andExpect(jsonPath("$.preview.length()").value(5));

        mvc.perform(get("/api/projects/{id}", id)).andExpect(jsonPath("$.currentStep").value(3));

        mvc.perform(get("/api/projects/{id}/normalize", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configs[0].config.sheetName").value("ItemText"))
                .andExpect(jsonPath("$.stats.normalized").value(5));
    }

    @Test
    void normalizeTwiceReplacesSegments() throws Exception {
        long id = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, id);
        TestProjects.normalize(mvc, id);
        mvc.perform(get("/api/projects/{id}/normalize", id)).andExpect(jsonPath("$.stats.totalRows").value(6));
    }

    @Test
    void normalizeRejectsUnknownSheet() throws Exception {
        long id = TestProjects.upload(mvc);
        mvc.perform(put("/api/projects/{id}/normalize", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sheetName\":\"Nope\",\"headerRow\":1,\"pairs\":[{\"srcCol\":\"C\",\"koColMode\":\"NEW\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sheetsCarryAFewPreviewRowsSoTheScreenCanShowThem() throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc);
        mvc.perform(get("/api/projects/{id}/sheets", pid).param("headerRow", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sheets[0].headers[1].text").value("CN"))
                // 헤더 아래 데이터 행이 순서대로, 최대 5줄
                .andExpect(jsonPath("$.sheets[0].preview.length()").value(5))
                .andExpect(jsonPath("$.sheets[0].preview[0][0]").value("211011101"))
                .andExpect(jsonPath("$.sheets[0].preview[0][1]").value("正义裁决"))
                .andExpect(jsonPath("$.sheets[0].preview[0][3]").value("정의의 심판"));
    }

    @Test
    void normalizePreviewIsPaged() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        mvc.perform(get("/api/projects/{id}/normalize", pid).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.preview.length()").value(2))
                .andExpect(jsonPath("$.preview[0].indexLabel").value("ItemText!4"));  // 5행은 원문이 비어 건너뜀
        mvc.perform(get("/api/projects/{id}/segments", pid).param("page", "2").param("size", "2"))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.rows.length()").value(1));
    }
}
