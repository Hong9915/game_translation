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
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 헤더 행이 없는 시트(headerRow 0): 1행부터 데이터이고, 내보내기도 머리글 글자를 쓰지 않는다. */
class HeaderlessNormalizeTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;

    private static final String EXISTING = """
        {"sheetName":"S","headerRow":0,"pairs":[{"srcCol":"A","koColMode":"EXISTING","koCol":"B"}]}
        """;
    private static final String TO_NEW = """
        {"sheetName":"S","headerRow":0,"pairs":[{"srcCol":"A","koColMode":"EXISTING_TO_NEW","koCol":"B","koHeader":"KR_Fix"}]}
        """;

    private long normalized(String body) throws Exception {
        long pid = TestProjects.uploadHeaderless(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        return pid;
    }

    private Workbook export(long pid, boolean flag) throws Exception {
        byte[] out = mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addFlagColumn\":" + flag + ",\"addHistorySheet\":false,\"addGlossarySheet\":false,\"ratio\":1.0}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        return WorkbookFactory.create(new ByteArrayInputStream(out));
    }

    private Segment seg(long pid, int rowNo) {
        return segments.findByProjectIdAndSheetNameAndSkippedFalseOrderByRowNoAscPairIndexAsc(pid, "S").stream()
                .filter(s -> s.getRowNo() == rowNo).findFirst().orElseThrow();
    }

    private void edit(long pid, int rowNo, String text) throws Exception {
        mvc.perform(put("/api/segments/{id}/translation", seg(pid, rowNo).getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}")).andExpect(status().isOk());
    }

    private static void assertNoText(Workbook wb, String... forbidden) {
        DataFormatter fmt = new DataFormatter();
        for (Sheet s : wb) for (Row r : s) for (Cell c : r) {
            String v = fmt.formatCellValue(c);
            for (String f : forbidden) assertThat(v).isNotEqualTo(f);
        }
    }

    @Test
    void readsFromTheFirstRow() throws Exception {
        long pid = normalized(EXISTING);
        var segs = segments.findByProjectIdAndSheetNameAndSkippedFalseOrderByRowNoAscPairIndexAsc(pid, "S");
        assertThat(segs).extracting(Segment::getIndexLabel).containsExactly("S!1", "S!2");
        assertThat(segs).extracting(Segment::getSrcText).containsExactly("开始", "结束");
    }

    @Test
    void sheetsEndpointGivesBlankHeadersAndPreviewFromRowOne() throws Exception {
        long pid = TestProjects.uploadHeaderless(mvc, TestProjects.freshGame());
        mvc.perform(get("/api/projects/{id}/sheets", pid).param("headerRow", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sheets[0].headers.length()").value(2))
                .andExpect(jsonPath("$.sheets[0].headers[0].letter").value("A"))
                .andExpect(jsonPath("$.sheets[0].headers[0].text").value(""))
                .andExpect(jsonPath("$.sheets[0].headers[1].letter").value("B"))
                .andExpect(jsonPath("$.sheets[0].headers[1].text").value(""))
                .andExpect(jsonPath("$.sheets[0].preview[0][0]").value("开始"))
                .andExpect(jsonPath("$.sheets[0].preview[0][1]").value("시작"));
    }

    @Test
    void negativeHeaderRowIsABadRequest() throws Exception {
        long pid = TestProjects.uploadHeaderless(mvc, TestProjects.freshGame());
        mvc.perform(get("/api/projects/{id}/sheets", pid).param("headerRow", "-1")).andExpect(status().isBadRequest());
    }

    @Test
    void exportWritesNoHeaderLabelsAndKeepsRowOneAsData() throws Exception {
        long pid = normalized(EXISTING);
        edit(pid, 1, "출발");
        try (Workbook wb = export(pid, true)) {
            Sheet s = wb.getSheet("S");
            assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("开始");
            assertThat(s.getRow(0).getCell(1).getStringCellValue()).isEqualTo("출발");
            assertNoText(wb, "UI_FLAG", "비고");
        }
    }

    @Test
    void newKoreanColumnGetsNoHeaderLabelEither() throws Exception {
        long pid = normalized(TO_NEW);
        try (Workbook wb = export(pid, false)) {
            assertThat(wb.getSheet("S").getRow(0).getCell(2).getStringCellValue()).isEqualTo("시작");
            assertNoText(wb, "KR_Fix");
        }
    }

    @Test
    void noteOnAHeaderlessSheetWritesTheTextButNoLabel() throws Exception {
        long pid = normalized(EXISTING);
        mvc.perform(put("/api/segments/{sid}/marks", seg(pid, 1).getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"메모\"}")).andExpect(status().isOk());
        try (Workbook wb = export(pid, true)) {
            assertThat(wb.getSheet("S").getRow(0).getCell(3).getStringCellValue()).isEqualTo("메모");
            assertNoText(wb, "UI_FLAG", "비고");
        }
    }

    @Test
    void exportPreviewWorksWithoutAHeaderRow() throws Exception {
        long pid = normalized(EXISTING);
        mvc.perform(get("/api/projects/{id}/export/preview", pid)).andExpect(status().isOk());
    }
}
