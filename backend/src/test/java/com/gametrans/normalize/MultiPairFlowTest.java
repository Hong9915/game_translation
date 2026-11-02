package com.gametrans.normalize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.llm.LlmClient;
import java.io.ByteArrayInputStream;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 한 파일에 원문 열이 여러 개(이름·설명)면 열 쌍을 여러 개 지정해 한 번에 처리하고 파일 하나로 받는다. */
class MultiPairFlowTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;

    @BeforeEach
    void setUp() {
        FakeLlmClient fake = (FakeLlmClient) llm;
        fake.translateCalls.clear(); fake.translations.clear(); fake.dropOnce.clear(); fake.breakPlaceholdersOnce.clear();
    }

    @Test
    void sheetsGuessBothPairs() throws Exception {
        long pid = TestProjects.uploadTwoPairs(mvc, null);
        mvc.perform(get("/api/projects/{id}/sheets", pid))
                .andExpect(jsonPath("$.sheets[0].guess.key").value("A"))
                .andExpect(jsonPath("$.sheets[0].guess.pairs.length()").value(2))
                .andExpect(jsonPath("$.sheets[0].guess.pairs[0].src").value("B"))
                .andExpect(jsonPath("$.sheets[0].guess.pairs[0].en").value("C"))
                .andExpect(jsonPath("$.sheets[0].guess.pairs[0].ko").value("D"))
                .andExpect(jsonPath("$.sheets[0].guess.pairs[1].src").value("E"))
                .andExpect(jsonPath("$.sheets[0].guess.pairs[1].ko").value("G"));
    }

    @Test
    void normalizeMakesOneSegmentPerCellAndKeepsTheRulesPerPair() throws Exception {
        long pid = TestProjects.uploadTwoPairs(mvc, null);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.TWO_PAIRS_NORMALIZE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configs[0].config.pairs.length()").value(2))
                .andExpect(jsonPath("$.configs[0].config.pairs[1].koCol").value("G"))
                .andExpect(jsonPath("$.stats.totalRows").value(6))
                .andExpect(jsonPath("$.stats.normalized").value(4))
                .andExpect(jsonPath("$.stats.skipped").value(2))
                .andExpect(jsonPath("$.stats.intentional").value(0))
                .andExpect(jsonPath("$.stats.kept").value(1))
                .andExpect(jsonPath("$.preview[0].indexLabel").value("Skill!2!B"))
                .andExpect(jsonPath("$.preview[0].srcText").value("正义裁决"))
                .andExpect(jsonPath("$.preview[0].existingKoText").value("정의의 심판"))
                .andExpect(jsonPath("$.preview[1].indexLabel").value("Skill!2!E"))
                .andExpect(jsonPath("$.preview[1].srcText").value("解放圣剑的力量"))
                .andExpect(jsonPath("$.preview[2].indexLabel").value("Skill!4!B"))
                .andExpect(jsonPath("$.preview[3].indexLabel").value("Skill!4!E"));
    }

    @Test
    void rejectsTwoPairsWritingTheSameColumnOrNoPairs() throws Exception {
        long pid = TestProjects.uploadTwoPairs(mvc, null);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.TWO_PAIRS_NORMALIZE.replace("\"koCol\":\"G\"", "\"koCol\":\"D\""))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sheetName\":\"Skill\",\"headerRow\":1,\"pairs\":[]}")).andExpect(status().isBadRequest());
    }

    @Test
    void translateAndExportFillBothKoreanColumnsInOneFile() throws Exception {
        long pid = TestProjects.uploadTwoPairs(mvc, null);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.TWO_PAIRS_NORMALIZE)).andExpect(status().isOk());
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}")).andExpect(status().isOk());
        FakeLlmClient fake = (FakeLlmClient) llm;
        assertThat(fake.translateCalls.get(0).rows()).extracting(LlmClient.TranslateRow::index).containsExactly("Skill!2!E", "Skill!4!B", "Skill!4!E");

        mvc.perform(get("/api/projects/{id}/export/preview", pid))
                .andExpect(jsonPath("$.koColumns.length()").value(2))
                .andExpect(jsonPath("$.koColumns[0].letter").value("D"))
                .andExpect(jsonPath("$.koColumns[0].newColumn").value(false))
                .andExpect(jsonPath("$.koColumns[1].letter").value("G"))
                .andExpect(jsonPath("$.headers[3].kind").value("KO"))
                .andExpect(jsonPath("$.headers[6].kind").value("KO"))
                .andExpect(jsonPath("$.headers[7].kind").value("FLAG"))
                .andExpect(jsonPath("$.filledRows").value(4))
                .andExpect(jsonPath("$.totalRows").value(4))
                .andExpect(jsonPath("$.rows.length()").value(2))          // 시트 행 기준(2행·4행)
                .andExpect(jsonPath("$.rows[0][0]").value("211011101"))
                .andExpect(jsonPath("$.rows[0][3]").value("정의의 심판"))
                .andExpect(jsonPath("$.rows[0][6]").value("KO:解放圣剑的力量"))
                .andExpect(jsonPath("$.rows[1][3]").value("KO:专属天赋"));

        byte[] body = mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addFlagColumn\":true,\"addHistorySheet\":true,\"addGlossarySheet\":false,\"ratio\":1.0}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            Sheet s = wb.getSheet("Skill");
            assertThat(s.getRow(1).getCell(3).getStringCellValue()).isEqualTo("정의의 심판");
            assertThat(s.getRow(1).getCell(6).getStringCellValue()).isEqualTo("KO:解放圣剑的力量");
            assertThat(s.getRow(2).getCell(3).getStringCellValue()).isEqualTo("N/A");
            assertThat(s.getRow(2).getCell(6).getStringCellValue()).isEqualTo("N/A");
            assertThat(s.getRow(3).getCell(3).getStringCellValue()).isEqualTo("KO:专属天赋");
            assertThat(s.getRow(3).getCell(6).getStringCellValue()).isEqualTo("KO:伤害减免提升至@10%@");
            assertThat(s.getRow(0).getCell(7).getStringCellValue()).isEqualTo("UI_FLAG");
            assertThat(wb.getSheet("수정 이력").getRow(1).getCell(0).getStringCellValue()).isEqualTo("Skill!2!B");
        }
    }
}
