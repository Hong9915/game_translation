package com.gametrans.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import java.io.ByteArrayInputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 검수 비고는 내보내기 파일의 "비고" 열로 나간다(설계 2026-09-28 §5-1). */
class ExportNoteTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;

    /** twoPairs(): 이름 쌍 B/C→D, 설명 쌍 E/F→G. 데이터가 G(6)까지라 플래그 열은 H(7). */
    private long twoPairs() throws Exception {
        long pid = TestProjects.uploadTwoPairs(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.TWO_PAIRS_NORMALIZE))
                .andExpect(status().isOk());
        return pid;
    }

    private Segment seg(long pid, int rowNo, int pair) {
        return segments.findByProjectIdAndSheetNameAndSkippedFalseOrderByRowNoAscPairIndexAsc(pid, "Skill").stream()
                .filter(s -> s.getRowNo() == rowNo && s.getPairIndex() == pair).findFirst().orElseThrow();
    }

    private void note(Segment s, String text) throws Exception {
        mvc.perform(put("/api/segments/{sid}/marks", s.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"" + text + "\"}")).andExpect(status().isOk());
    }

    private Sheet export(long pid, boolean flag, Workbook[] hold) throws Exception {
        byte[] body = mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addFlagColumn\":" + flag + ",\"addHistorySheet\":false,\"addGlossarySheet\":false,\"ratio\":1.0}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        hold[0] = WorkbookFactory.create(new ByteArrayInputStream(body));
        return hold[0].getSheet("Skill");
    }

    private static String text(Row row, int col) {
        Cell c = row == null ? null : row.getCell(col);
        return c == null ? null : c.getStringCellValue();
    }

    @Test
    void notesGoToANoteColumnJoinedPerRow() throws Exception {
        long pid = twoPairs();
        note(seg(pid, 2, 0), "이름 확인");
        note(seg(pid, 2, 1), "설명 길이");
        note(seg(pid, 4, 0), "빈 한국어 행");   // 专属天赋: 기존 한국어가 비어 Fix가 없는 칸
        Workbook[] wb = new Workbook[1];
        Sheet s = export(pid, false, wb);
        try (Workbook ignored = wb[0]) {
            assertThat(text(s.getRow(0), 7)).isEqualTo("비고");
            assertThat(text(s.getRow(1), 7)).isEqualTo("이름 확인 / 설명 길이");
            assertThat(text(s.getRow(2), 7)).isNull();
            assertThat(text(s.getRow(3), 7)).isEqualTo("빈 한국어 행");
        }
    }

    @Test
    void theNoteColumnFollowsTheFlagColumn() throws Exception {
        long pid = twoPairs();
        note(seg(pid, 2, 0), "메모");
        Workbook[] wb = new Workbook[1];
        Sheet s = export(pid, true, wb);
        try (Workbook ignored = wb[0]) {
            assertThat(text(s.getRow(0), 7)).isEqualTo("UI_FLAG");
            assertThat(text(s.getRow(0), 8)).isEqualTo("비고");
            assertThat(text(s.getRow(1), 8)).isEqualTo("메모");
        }
    }

    @Test
    void noNoteNoColumn() throws Exception {
        long pid = twoPairs();
        Workbook[] wb = new Workbook[1];
        Sheet s = export(pid, false, wb);
        try (Workbook ignored = wb[0]) {
            assertThat(text(s.getRow(0), 7)).isNull();
        }
        mvc.perform(get("/api/projects/{id}/export/preview", pid))
                .andExpect(jsonPath("$.headers[?(@.kind == 'NOTE')]").isEmpty());
    }

    @Test
    void theGameZipLeavesTheNoteColumnOut() throws Exception {
        long pid = twoPairs();
        note(seg(pid, 2, 0), "메모");
        byte[] body = mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addFlagColumn\":false,\"addHistorySheet\":false,\"addGlossarySheet\":false,\"ratio\":1.0,\"addNoteColumn\":false}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            assertThat(text(wb.getSheet("Skill").getRow(0), 7)).isNull();
        }
    }

    @Test
    void thePreviewShowsTheNoteColumn() throws Exception {
        long pid = twoPairs();
        note(seg(pid, 2, 1), "설명 길이");
        mvc.perform(get("/api/projects/{id}/export/preview", pid))
                .andExpect(jsonPath("$.headers[-1:].kind").value(org.hamcrest.Matchers.contains("NOTE")))
                .andExpect(jsonPath("$.headers[-1:].text").value(org.hamcrest.Matchers.contains("비고")))
                .andExpect(jsonPath("$.rows[0][-1:]").value(org.hamcrest.Matchers.contains("설명 길이")));
    }
}
