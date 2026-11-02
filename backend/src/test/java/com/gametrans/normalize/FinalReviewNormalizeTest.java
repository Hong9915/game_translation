package com.gametrans.normalize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/**
 * 최종 리뷰 버전: 기존 한국어(C)와 이미 고친 한국어(D, fixCol)를 함께 읽는다.
 * finalReview() 워크북: 2행 같음, 3행 고침(재산→속성), 4행 기존 없이 고침, 5행 고친 칸 빔, 6행 둘 다 빔.
 */
class FinalReviewNormalizeTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;

    private static final String NORMALIZE = TestProjects.FINAL_REVIEW_NORMALIZE;

    private long normalized() throws Exception {
        long pid = TestProjects.uploadFinalReview(mvc);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(NORMALIZE))
                .andExpect(status().isOk());
        return pid;
    }

    @Test
    void readsExistingAndFixedKoreanSoTheGridShowsBoth() throws Exception {
        long pid = normalized();
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Sheet1"))
                .andExpect(jsonPath("$.total").value(5))
                // 같음: 기존이 그대로 Fix, 수정 아님
                .andExpect(jsonPath("$.rows[0].existingKo").value("게임을 종료하시겠습니까?"))
                .andExpect(jsonPath("$.rows[0].fix").value("게임을 종료하시겠습니까?"))
                .andExpect(jsonPath("$.rows[0].changed").value(false))
                .andExpect(jsonPath("$.rows[0].history.length()").value(1))
                // 고침: 기존은 0차, 고친 문장이 Fix — 수정됨으로 보인다
                .andExpect(jsonPath("$.rows[1].existingKo").value("재산"))
                .andExpect(jsonPath("$.rows[1].fix").value("속성"))
                .andExpect(jsonPath("$.rows[1].changed").value(true))
                .andExpect(jsonPath("$.rows[1].history[0].author").value("EXISTING"))
                .andExpect(jsonPath("$.rows[1].history[1].author").value("IMPORTED"))
                // 기존 없이 고침
                .andExpect(jsonPath("$.rows[2].existingKo").doesNotExist())
                .andExpect(jsonPath("$.rows[2].fix").value("분해 성공"))
                // 고친 칸이 비면 기존을 그대로 둔다
                .andExpect(jsonPath("$.rows[3].fix").value("판매"))
                // 둘 다 비면 Fix도 비어 번역 대상이다
                .andExpect(jsonPath("$.rows[4].fix").doesNotExist());
        mvc.perform(get("/api/projects/{id}/normalize", pid).param("sheet", "Sheet1"))
                .andExpect(jsonPath("$.configs[0].config.pairs[0].fixCol").value("D"));
    }

    @Test
    void exportWritesTheFixBackIntoTheFixColumnWithoutAddingOne() throws Exception {
        long pid = normalized();
        String list = mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Sheet1")).andReturn().getResponse().getContentAsString();
        long sid = ((Number) JsonPath.parse(list).read("$.rows[1].id")).longValue();
        mvc.perform(put("/api/segments/{id}/translation", sid).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"능력치\"}"))
                .andExpect(status().isOk());

        byte[] out = mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addFlagColumn\":false,\"addHistorySheet\":false,\"addGlossarySheet\":false,\"ratio\":1.0}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out))) {
            Sheet s = wb.getSheet("Sheet1");
            assertThat(s.getRow(0).getCell(3).getStringCellValue()).isEqualTo("KR_Fix");   // D열 헤더 그대로
            assertThat(s.getRow(0).getCell(4)).isNull();                                    // 새 열 없음
            assertThat(s.getRow(2).getCell(2).getStringCellValue()).isEqualTo("재산");       // C: 기존 그대로
            assertThat(s.getRow(2).getCell(3).getStringCellValue()).isEqualTo("능력치");     // D: 지금 Fix
            assertThat(s.getRow(3).getCell(3).getStringCellValue()).isEqualTo("분해 성공");
        }
    }

    @Test
    void rejectsFixColumnWithoutExistingColumnOrOnTheSameColumn() throws Exception {
        long pid = TestProjects.uploadFinalReview(mvc);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content("""
                {"sheetName":"Sheet1","headerRow":1,"pairs":[{"srcCol":"B","koColMode":"NEW","fixCol":"D"}]}
                """)).andExpect(status().isBadRequest());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content("""
                {"sheetName":"Sheet1","headerRow":1,"pairs":[{"srcCol":"B","koColMode":"EXISTING_TO_NEW","koCol":"C","fixCol":"C"}]}
                """)).andExpect(status().isBadRequest());
    }

    @Test
    void marksTheSheetRowsAsFinalReviewAndBackToNormalOnPlainRenormalize() throws Exception {
        long pid = normalized();
        assertThat(segments.findAll().stream().filter(s -> s.getProjectId() == pid).allMatch(Segment::isFinalReview)).isTrue();
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content("""
                {"sheetName":"Sheet1","headerRow":1,"pairs":[{"srcCol":"B","koColMode":"EXISTING_TO_NEW","koCol":"C","koHeader":"KR_Fix"}]}
                """)).andExpect(status().isOk());
        assertThat(segments.findAll().stream().filter(s -> s.getProjectId() == pid).noneMatch(Segment::isFinalReview)).isTrue();
    }

    @Test
    void exportFillsOnlyFinalMarkedCellsGreenInsteadOfChangedYellow() throws Exception {
        long pid = normalized();
        String list = mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Sheet1")).andReturn().getResponse().getContentAsString();
        long marked = ((Number) JsonPath.parse(list).read("$.rows[0].id")).longValue();   // 2행: 기존 = 수정(달라지지 않음)
        mvc.perform(put("/api/segments/{id}/marks", marked).contentType(MediaType.APPLICATION_JSON).content("{\"finalMark\":true}"))
                .andExpect(status().isOk());
        byte[] out = mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addFlagColumn\":false,\"addHistorySheet\":false,\"addGlossarySheet\":false,\"ratio\":1.0}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out))) {
            Sheet s = wb.getSheet("Sheet1");
            assertThat(fill(s.getRow(1).getCell(3))).isEqualTo("E2F0D9");   // 표시한 행: 연두
            assertThat(fill(s.getRow(2).getCell(3))).isNull();             // 달라졌지만 표시 안 함: 칠하지 않음
        }
    }

    /** 칸의 단색 채우기 RGB(앞 알파 뺀 6자리). 채우기가 없으면 null. */
    private static String fill(org.apache.poi.ss.usermodel.Cell c) {
        var st = (org.apache.poi.xssf.usermodel.XSSFCellStyle) c.getCellStyle();
        var col = st.getFillForegroundXSSFColor();
        if (col == null || st.getFillPattern() != org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND) return null;
        String hex = col.getARGBHex();
        return hex.substring(hex.length() - 6);
    }
}
