package com.gametrans.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.llm.LlmClient;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import java.io.ByteArrayInputStream;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 내보내기는 기존 한국어와 달라진 칸만 연노랑으로 채운다. 기존 한국어가 없던 새 열(NEW)은 비교 대상이 없어 칠하지 않는다. */
class ExportHighlightTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;
    @Autowired SegmentRepository segments;

    @BeforeEach
    void setUp() {
        FakeLlmClient fake = (FakeLlmClient) llm;
        fake.dropOnce.clear();
        fake.breakPlaceholdersOnce.clear();
        fake.translations.clear();
        fake.translations.put("破晓之盾", "새벽의 방패");   // Quest!3 이름 — 기존 KR 빈칸이라 새로 채워진다
        fake.translations.put("冒险开始", "모험이 시작된다");  // Quest!2 설명 — 새 열
        fake.translations.put("限时特惠", "한정 특가");     // Quest!3 설명 — 새 열
    }

    private Sheet exportQuest(long pid, Workbook[] hold) throws Exception {
        byte[] body = mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addFlagColumn\":false,\"addHistorySheet\":false,\"addGlossarySheet\":false,\"ratio\":2.0}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        hold[0] = WorkbookFactory.create(new ByteArrayInputStream(body));
        return hold[0].getSheet("Quest");
    }

    private static FillPatternType fill(Sheet s, int row, int col) { return s.getRow(row).getCell(col).getCellStyle().getFillPattern(); }

    /**
     * Quest 시트: 이름은 기존 D열, 설명은 새 G열. 2행 이름(서리)은 기존 번역 유지라 그대로, 3행 이름은 빈칸에서
     * 새로 채워져 색칠. 설명 열은 새 열이라 둘 다 칠하지 않는다.
     */
    @Test
    void onlyCellsThatDifferFromTheExistingKoreanAreHighlighted() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.QUEST_NORMALIZE))
                .andExpect(status().isOk());
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"))
                .andExpect(status().isOk());

        Workbook[] wb = new Workbook[1];
        Sheet quest = exportQuest(pid, wb);
        try (Workbook ignored = wb[0]) {
            assertThat(quest.getRow(1).getCell(3).getStringCellValue()).isEqualTo("서리");
            assertThat(fill(quest, 1, 3)).isEqualTo(FillPatternType.NO_FILL);
            assertThat(quest.getRow(2).getCell(3).getStringCellValue()).isEqualTo("새벽의 방패");
            assertThat(fill(quest, 2, 3)).isEqualTo(FillPatternType.SOLID_FOREGROUND);
            assertThat(quest.getRow(1).getCell(6).getStringCellValue()).isEqualTo("모험이 시작된다");
            assertThat(fill(quest, 1, 6)).isEqualTo(FillPatternType.NO_FILL);
            assertThat(fill(quest, 2, 6)).isEqualTo(FillPatternType.NO_FILL);
        }
    }

    /** 사람이 기존 번역을 고치면 그 칸이 색칠되고, 다시 원래대로 돌리면(공백 차이만 남아도) 색이 빠진다. */
    @Test
    void editingAKeptTranslationHighlightsItAndRestoringItClearsTheHighlight() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.QUEST_NORMALIZE))
                .andExpect(status().isOk());
        Segment name2 = segments.findByProjectIdAndSheetNameAndSkippedFalseOrderByRowNoAscPairIndexAsc(pid, "Quest").stream()
                .filter(s -> s.getRowNo() == 2 && s.getPairIndex() == 0).findFirst().orElseThrow();

        mvc.perform(put("/api/segments/{sid}/translation", name2.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"서리의 속삭임\"}")).andExpect(status().isOk());
        Workbook[] wb = new Workbook[1];
        Sheet quest = exportQuest(pid, wb);
        try (Workbook ignored = wb[0]) {
            assertThat(quest.getRow(1).getCell(3).getStringCellValue()).isEqualTo("서리의 속삭임");
            assertThat(fill(quest, 1, 3)).isEqualTo(FillPatternType.SOLID_FOREGROUND);
        }

        mvc.perform(put("/api/segments/{sid}/translation", name2.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\" 서리 \"}")).andExpect(status().isOk());
        quest = exportQuest(pid, wb);
        try (Workbook ignored = wb[0]) {
            assertThat(fill(quest, 1, 3)).isEqualTo(FillPatternType.NO_FILL);
        }
    }
}
