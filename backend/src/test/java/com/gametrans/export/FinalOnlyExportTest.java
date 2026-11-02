package com.gametrans.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 최종 수정분만 내보내기(2026-10-19): 최종 수정 표시를 켠 행만, 원본과 같은 열·머리글로. finalReview() 워크북은 검수 대상 5행. */
class FinalOnlyExportTest extends IntegrationTest {
    @Autowired MockMvc mvc;

    static final String FINAL_ONLY = "{\"addFlagColumn\":false,\"addHistorySheet\":false,\"addGlossarySheet\":false,\"ratio\":2.0,\"finalOnly\":true}";
    static final String FULL = "{\"addFlagColumn\":false,\"addHistorySheet\":false,\"addGlossarySheet\":false,\"ratio\":2.0}";

    long project() throws Exception {
        long pid = TestProjects.uploadFinalReview(mvc);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.FINAL_REVIEW_NORMALIZE))
                .andExpect(status().isOk());
        return pid;
    }

    void mark(long pid, int... idx) throws Exception {
        String list = mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Sheet1")).andReturn().getResponse().getContentAsString();
        for (int i : idx) {
            long sid = ((Number) JsonPath.parse(list).read("$.rows[" + i + "].id")).longValue();
            mvc.perform(put("/api/segments/{id}/marks", sid).contentType(MediaType.APPLICATION_JSON).content("{\"finalMark\":true}"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void exportsOnlyMarkedRowsUnderTheOriginalHeader() throws Exception {
        long pid = project();
        mark(pid, 1, 3);   // Hero.ts(3행), 出售(5행)
        byte[] body = mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON).content(FINAL_ONLY))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("_1")))
                .andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(1);
            Sheet s = wb.getSheet("Sheet1");
            assertThat(s.getLastRowNum()).isEqualTo(2);
            assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("file_name");
            assertThat(s.getRow(0).getCell(3).getStringCellValue()).isEqualTo("KR_Fix");
            assertThat(s.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Hero.ts");
            assertThat(s.getRow(1).getCell(1).getStringCellValue()).isEqualTo("属性");
            assertThat(s.getRow(1).getCell(3).getStringCellValue()).isEqualTo("속성");
            assertThat(s.getRow(2).getCell(1).getStringCellValue()).isEqualTo("出售");
        }
    }

    @Test
    void finalOnlyVersionsAreCountedApartAndNamedSo() throws Exception {
        long pid = project();
        mark(pid, 1);
        mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON).content(FULL)).andExpect(status().isOk());
        mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON).content(FINAL_ONLY)).andExpect(status().isOk());
        mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON).content(FINAL_ONLY)).andExpect(status().isOk());
        mvc.perform(get("/api/projects/{id}/export/versions", pid))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.fileName=='UI文本_261012(KR)_최종수정분_2차.xlsx')]").exists())
                .andExpect(jsonPath("$[?(@.fileName=='UI文本_261012(KR)_1차수정본.xlsx')]").exists());
    }

    @Test
    void previewCountsMarkedRowsAndNamesTheFinalFile() throws Exception {
        long pid = project();
        mark(pid, 1, 3);
        mvc.perform(get("/api/projects/{id}/export/preview", pid).param("ratio", "2.0"))
                .andExpect(jsonPath("$.finalMarkedRows").value(2))
                .andExpect(jsonPath("$.finalOutputFileName").value("UI文本_261012(KR)_최종수정분_1차.xlsx"));
    }

    @Test
    void gameScreenSheetExportCanBeFinalOnlyToo() throws Exception {
        long pid = project();
        mark(pid, 1);
        mvc.perform(get("/api/projects/{id}/export/preview", pid).param("ratio", "2.0").param("sheet", "Sheet1").param("single", "true"))
                .andExpect(jsonPath("$.finalMarkedRows").value(1))
                .andExpect(jsonPath("$.finalOutputFileName").value("UI文本_261012(KR)_Sheet1_최종수정분_1차.xlsx"));
        byte[] body = mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON)
                        .content(FINAL_ONLY.replace("}", ",\"sheet\":\"Sheet1\"}")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            assertThat(wb.getSheet("Sheet1").getLastRowNum()).isEqualTo(1);
        }
    }

    @Test
    void nothingMarkedIsRejected() throws Exception {
        long pid = project();
        mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON).content(FINAL_ONLY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fileWithoutFinalReviewSheetHasNoFinalCount() throws Exception {
        long pid = TestProjects.uploadProofread(mvc);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.PROOFREAD_NORMALIZE))
                .andExpect(status().isOk());
        mvc.perform(get("/api/projects/{id}/export/preview", pid).param("ratio", "2.0"))
                .andExpect(jsonPath("$.finalMarkedRows").doesNotExist());
        mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON).content(FINAL_ONLY))
                .andExpect(status().isBadRequest());
    }
}
