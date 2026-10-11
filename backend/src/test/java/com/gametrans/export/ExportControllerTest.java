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
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class ExportControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;

    @BeforeEach
    void setUp() {
        FakeLlmClient fake = (FakeLlmClient) llm;
        fake.dropOnce.clear();
        fake.breakPlaceholdersOnce.clear();
        fake.translations.clear();
        fake.translations.put("烈焰之剑", "화염의 검");
    }

    @Test
    void previewAndExport() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"));

        mvc.perform(get("/api/projects/{id}/export/preview", pid).param("ratio", "1.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outputFileName").value("ItemText_v3_1차수정본.xlsx"))
                .andExpect(jsonPath("$.sheetName").value("ItemText"))
                .andExpect(jsonPath("$.otherSheetCount").value(1))
                .andExpect(jsonPath("$.koColumns.length()").value(1))
                .andExpect(jsonPath("$.koColumns[0].letter").value("E"))
                .andExpect(jsonPath("$.koColumns[0].newColumn").value(true))
                .andExpect(jsonPath("$.koColumns[0].header").value("KO"))
                .andExpect(jsonPath("$.filledRows").value(5))
                .andExpect(jsonPath("$.totalRows").value(5))
                .andExpect(jsonPath("$.headers[4].kind").value("KO"))
                .andExpect(jsonPath("$.headers[5].kind").value("FLAG"))
                .andExpect(jsonPath("$.rows[0][0]").value("IT_10001"))
                .andExpect(jsonPath("$.rows[0][4]").value("화염의 검"));

        byte[] body = mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addFlagColumn\":true,\"addHistorySheet\":true,\"addGlossarySheet\":false,\"ratio\":1.0}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("ItemText_v3_1")))
                .andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            assertThat(wb.getSheet("ItemText").getRow(1).getCell(4).getStringCellValue()).isEqualTo("화염의 검");
            assertThat(wb.getSheet("수정 이력")).isNotNull();
        }
        mvc.perform(get("/api/projects/{id}", pid)).andExpect(jsonPath("$.currentStep").value(6));
    }

    /** 이력 시트의 맨 끝 열은 사람이 고친 판에는 검토자 이름을, LLM 판에는 빈 칸을 담아야 한다. */
    @Test
    void historySheetLastColumnHasReviewerNameOnlyForHumanRevisions() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"));

        String reviewerName = "지민-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        String reviewerBody = mvc.perform(post("/api/reviewers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + reviewerName + "\"}"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long rid = ((Number) JsonPath.parse(reviewerBody).read("$.id")).longValue();

        String segList = mvc.perform(get("/api/projects/{id}/segments", pid))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long sid = ((Number) JsonPath.parse(segList).read("$.rows[2].id")).longValue();
        String indexLabel = JsonPath.parse(segList).read("$.rows[2].indexLabel");

        mvc.perform(put("/api/segments/{id}/translation", sid).header("X-Reviewer-Id", rid)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"서리\"}"))
                .andExpect(status().isOk());

        byte[] body = mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addFlagColumn\":false,\"addHistorySheet\":true,\"addGlossarySheet\":false,\"ratio\":1.0}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            Sheet history = wb.getSheet("수정 이력");
            int lastCol = history.getRow(0).getLastCellNum() - 1;
            boolean sawLlmBlank = false;
            boolean sawHumanName = false;
            for (int i = 1; i <= history.getLastRowNum(); i++) {
                Row row = history.getRow(i);
                if (row == null || !indexLabel.equals(row.getCell(0).getStringCellValue())) continue;
                String author = row.getCell(2).getStringCellValue();
                Cell reviewerCell = row.getCell(lastCol);
                if ("LLM".equals(author)) {
                    assertThat(reviewerCell == null || reviewerCell.getCellType() == CellType.BLANK).isTrue();
                    sawLlmBlank = true;
                } else if ("HUMAN".equals(author)) {
                    assertThat(reviewerCell.getStringCellValue()).isEqualTo(reviewerName);
                    sawHumanName = true;
                }
            }
            assertThat(sawLlmBlank).isTrue();
            assertThat(sawHumanName).isTrue();
        }
    }

    @Test
    void csvProjectExportsCsv() throws Exception {
        String csv = "ID,CN,EN\r\n1,烈焰之剑,Blazing Sword\r\n";
        org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
                "file", "items.csv", "text/csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/projects").file(file).param("gameName", com.gametrans.TestProjects.freshGame()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long pid = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(body).get("id").asLong();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sheetName\":\"items\",\"headerRow\":1,\"keyCol\":\"A\",\"pairs\":[{\"srcCol\":\"B\",\"enCol\":\"C\",\"koColMode\":\"NEW\",\"koHeader\":\"KO\"}]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"));

        mvc.perform(get("/api/projects/{id}/export/preview", pid))
                .andExpect(jsonPath("$.outputFileName").value("items_1차수정본.csv"));
        byte[] out = mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addFlagColumn\":false,\"addHistorySheet\":true,\"addGlossarySheet\":false,\"ratio\":1.0}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("items_1")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(out, java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("\uFEFFID,CN,EN,KO\r\n1,烈焰之剑,Blazing Sword,화염의 검\r\n");
    }

    /** 내보낼 때마다 1차·2차 수정본으로 쌓이고, 저장된 버전과 원본을 다시 받을 수 있다. */
    @Test
    void eachExportBecomesANumberedVersionThatCanBeDownloadedAgain() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"));
        mvc.perform(get("/api/projects/{id}/export/versions", pid)).andExpect(jsonPath("$.length()").value(0));

        String opts = "{\"addFlagColumn\":true,\"addHistorySheet\":false,\"addGlossarySheet\":false,\"ratio\":1.0}";
        mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON).content(opts)).andExpect(status().isOk());
        mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON).content(opts))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("ItemText_v3_2")));

        String list = mvc.perform(get("/api/projects/{id}/export/versions", pid))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].fileName").value("ItemText_v3_2차수정본.xlsx"))
                .andExpect(jsonPath("$[1].version").value(1))
                .andExpect(jsonPath("$[1].filledRows").value(5))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long v1 = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(list).get(1).get("id").asLong();
        byte[] again = mvc.perform(get("/api/projects/{id}/export/versions/{vid}", pid, v1))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("ItemText_v3_1")))
                .andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(again))) {
            assertThat(wb.getSheet("ItemText").getRow(1).getCell(4).getStringCellValue()).isEqualTo("화염의 검");
        }
        mvc.perform(get("/api/projects/{id}/export/original", pid))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("ItemText_v3.xlsx")));
        mvc.perform(get("/api/projects/{id}/export/versions/{vid}", pid, 999999)).andExpect(status().isNotFound());
    }
}
