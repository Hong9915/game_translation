package com.gametrans.xlsx;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** 최종 수정분만(2026-10-19): 머리글 줄과 고른 행만 남겨 위로 붙이고, 고르지 않은 시트는 지운다. */
class XlsxKeepRowsTest {
    static byte[] book() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet a = wb.createSheet("A");
            for (int i = 0; i < 10; i++) {
                Row r = a.createRow(i);
                r.createCell(0).setCellValue(i == 0 ? "ID" : "id" + (i + 1));
                r.createCell(1).setCellValue(i == 0 ? "KR" : "ko" + (i + 1));
            }
            wb.createSheet("B").createRow(0).createCell(0).setCellValue("다른 시트");
            wb.write(out);
            return out.toByteArray();
        }
    }

    static List<String> column(Sheet s, int col) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i <= s.getLastRowNum(); i++) out.add(s.getRow(i) == null ? null : s.getRow(i).getCell(col).getStringCellValue());
        return out;
    }

    @Test
    void keepsHeaderAndChosenRowsCompactedInOrder() throws Exception {
        byte[] kept = XlsxExporter.keepRows(book(), Map.of("A", 1), Map.of("A", Set.of(3, 4, 7, 10)));
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(kept))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(1);
            Sheet a = wb.getSheet("A");
            assertThat(column(a, 0)).containsExactly("ID", "id3", "id4", "id7", "id10");
            assertThat(column(a, 1)).containsExactly("KR", "ko3", "ko4", "ko7", "ko10");
        }
    }
}
