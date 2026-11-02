package com.gametrans.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.llm.LlmClient;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 시트 하나만 내보내기(설계 2026-10-12 §4). twoSheets()의 Items(3행)·Skills(2행)를 설정·번역한 파일. */
class SheetExportTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;

    @BeforeEach
    void setUp() {
        FakeLlmClient fake = (FakeLlmClient) llm;
        fake.dropOnce.clear();
        fake.breakPlaceholdersOnce.clear();
        fake.translations.clear();
        fake.translations.put("烈焰之剑", "화염검");
        fake.translations.put("霜语者", "서리");
        fake.translations.put("破晓之盾", "새벽의 방패");
        fake.translations.put("正义裁决", "정의 판결");
    }

    long project() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());
        for (String sheet : List.of("Items", "Skills"))
            mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.sheetNormalize(sheet)))
                    .andExpect(status().isOk());
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"))
                .andExpect(status().isOk());
        return pid;
    }

    byte[] export(long pid, String sheet, boolean history) throws Exception {
        String sheetJson = sheet == null ? "" : ",\"sheet\":\"" + sheet + "\"";
        return mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addFlagColumn\":false,\"addHistorySheet\":" + history + ",\"addGlossarySheet\":false,\"ratio\":2.0" + sheetJson + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    static List<String> sheets(byte[] body) throws Exception {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) out.add(wb.getSheetName(i));
            return out;
        }
    }

    @Test
    void sheetExportKeepsOnlyThatSheetFilled() throws Exception {
        long pid = project();
        byte[] body = export(pid, "Skills", true);
        assertThat(sheets(body)).containsExactly("Skills", "수정 이력");
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            assertThat(wb.getSheet("Skills").getRow(1).getCell(3).getStringCellValue()).isEqualTo("정의 판결");
            // 이력은 그 시트 행만
            var h = wb.getSheet("수정 이력");
            for (int i = 1; i <= h.getLastRowNum(); i++) assertThat(h.getRow(i).getCell(0).getStringCellValue()).startsWith("Skills!");
        }
    }

    @Test
    void sheetVersionsAreCountedApartFromWholeFileVersions() throws Exception {
        long pid = project();
        mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addFlagColumn\":false,\"addHistorySheet\":false,\"addGlossarySheet\":false,\"ratio\":2.0,\"sheet\":\"Skills\"}"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("MultiSheet_Skills_1")));
        export(pid, null, false);
        export(pid, "Skills", false);
        export(pid, null, false);
        mvc.perform(get("/api/projects/{id}/export/versions", pid))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[?(@.sheetName=='Skills')].version").value(org.hamcrest.Matchers.containsInAnyOrder(1, 2)))
                .andExpect(jsonPath("$[?(@.sheetName==null)].version").value(org.hamcrest.Matchers.containsInAnyOrder(1, 2)));
    }

    @Test
    void singlePreviewCountsOnlyThatSheet() throws Exception {
        long pid = project();
        mvc.perform(get("/api/projects/{id}/export/preview", pid).param("ratio", "2.0").param("sheet", "Skills").param("single", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sheetName").value("Skills"))
                .andExpect(jsonPath("$.sheets.length()").value(1))
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.outputFileName").value("MultiSheet_Skills_1차수정본.xlsx"));
    }

    @Test
    void unknownSheetIsRejected() throws Exception {
        long pid = project();
        mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addFlagColumn\":false,\"addHistorySheet\":false,\"addGlossarySheet\":false,\"sheet\":\"기타 시트\"}"))
                .andExpect(status().isBadRequest());
    }
}
