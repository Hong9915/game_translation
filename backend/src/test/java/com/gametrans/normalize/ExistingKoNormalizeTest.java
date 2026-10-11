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
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 이미 한국어 열이 있는 교정용 파일: 있는 번역은 살리고, N/A·None·빈칸만 골라 번역한다. */
class ExistingKoNormalizeTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;

    @BeforeEach
    void setUp() {
        FakeLlmClient fake = (FakeLlmClient) llm;
        fake.translateCalls.clear();
        fake.translations.clear();
        fake.dropOnce.clear();
        fake.breakPlaceholdersOnce.clear();
    }

    private long normalized() throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE))
                .andExpect(status().isOk());
        return pid;
    }

    @Test
    void statsSeparateKeptAndIntentionallyUntranslatedRows() throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.totalRows").value(6))
                .andExpect(jsonPath("$.stats.normalized").value(4))   // 2, 4, 5, 7행
                .andExpect(jsonPath("$.stats.skipped").value(2))      // 3행(의도적 N/A) + 6행(원문 none)
                .andExpect(jsonPath("$.stats.intentional").value(1))  // 3행
                .andExpect(jsonPath("$.stats.kept").value(1))         // 2행
                .andExpect(jsonPath("$.stats.withEn").value(3))       // N/A 영어는 세지 않는다
                .andExpect(jsonPath("$.configs[0].config.retranslateExisting").value(false))
                .andExpect(jsonPath("$.preview.length()").value(4))
                .andExpect(jsonPath("$.preview[0].indexLabel").value("Skill!2"))
                .andExpect(jsonPath("$.preview[0].existingKoText").value("정의의 심판"))
                .andExpect(jsonPath("$.preview[1].indexLabel").value("Skill!4"))
                .andExpect(jsonPath("$.preview[1].enText").value("DMG Reduction increased to @10%@"))
                .andExpect(jsonPath("$.preview[1].existingKoText").value("N/A"))
                .andExpect(jsonPath("$.preview[2].indexLabel").value("Skill!5"))
                .andExpect(jsonPath("$.preview[3].indexLabel").value("Skill!7"));
    }

    @Test
    void existingKoreanBecomesRevisionZeroAndIsNotRetranslated() throws Exception {
        long pid = normalized();
        mvc.perform(get("/api/projects/{id}/segments", pid))
                .andExpect(jsonPath("$.stats.total").value(4))
                .andExpect(jsonPath("$.stats.filled").value(1))
                .andExpect(jsonPath("$.rows[0].indexLabel").value("Skill!2"))
                .andExpect(jsonPath("$.rows[0].currentText").value("정의의 심판"))
                .andExpect(jsonPath("$.rows[0].history[0].author").value("EXISTING"))
                .andExpect(jsonPath("$.rows[1].currentText").doesNotExist());

        mvc.perform(get("/api/projects/{id}/translate", pid))
                .andExpect(jsonPath("$.targetRows").value(4))
                .andExpect(jsonPath("$.doneRows").value(1));

        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"))
                .andExpect(status().isOk());
        FakeLlmClient fake = (FakeLlmClient) llm;
        assertThat(fake.translateCalls).hasSize(1);
        assertThat(fake.translateCalls.get(0).rows()).extracting(r -> r.index())
                .containsExactly("Skill!4", "Skill!5", "Skill!7");
        // 영어 열의 N/A는 영어본으로 넘기지 않는다
        assertThat(fake.translateCalls.get(0).rows().get(1).en()).isNull();
    }

    @Test
    void retranslateExistingDropsTheOldKoreanFromRevisions() throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                        .content(TestProjects.EXISTING_KO_NORMALIZE.replace("\"retranslateExisting\":false", "\"retranslateExisting\":true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.kept").value(0))
                .andExpect(jsonPath("$.stats.normalized").value(4))
                .andExpect(jsonPath("$.preview[0].existingKoText").value("정의의 심판"));
        mvc.perform(get("/api/projects/{id}/segments", pid))
                .andExpect(jsonPath("$.stats.filled").value(0));
    }

    @Test
    void exportLeavesKeptAndIntentionalCellsUntouched() throws Exception {
        long pid = normalized();
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"))
                .andExpect(status().isOk());
        byte[] body = mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addFlagColumn\":false,\"addHistorySheet\":false,\"addGlossarySheet\":false,\"ratio\":1.0}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            var sheet = wb.getSheet("Skill");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("정의의 심판");            // 기존 번역 유지
            assertThat(sheet.getRow(2).getCell(3).getStringCellValue()).isEqualTo("N/A");                  // 의도적 N/A 유지
            assertThat(sheet.getRow(3).getCell(3).getStringCellValue()).isEqualTo("KO:伤害减免提升至@10%@"); // N/A → 번역
            assertThat(sheet.getRow(4).getCell(3).getStringCellValue()).isEqualTo("KO:技能伤害提升至@420%@"); // 빈칸 → 번역
            assertThat(sheet.getRow(5).getCell(3).getStringCellValue()).isEqualTo("None");                 // 원문 none → 손대지 않음
            assertThat(sheet.getRow(6).getCell(3).getStringCellValue()).isEqualTo("KO:霜语者");             // None → 번역
        }
    }
}
