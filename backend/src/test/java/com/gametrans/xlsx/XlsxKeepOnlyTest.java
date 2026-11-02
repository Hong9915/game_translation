package com.gametrans.xlsx;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** 시트 하나만 남기기(설계 2026-10-12 §4): 다른 원본 시트는 지우고, 지운 시트를 가리키던 수식은 값으로 남긴다. */
class XlsxKeepOnlyTest {
    static byte[] book() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet a = wb.createSheet("A"), b = wb.createSheet("B");
            b.createRow(0).createCell(0).setCellValue(42);
            Row r = a.createRow(0);
            r.createCell(0).setCellFormula("B!A1*2");
            r.createCell(1).setCellValue(5);
            r.createCell(2).setCellFormula("B1+1");
            wb.createSheet(XlsxExporter.HISTORY_SHEET);
            var name = wb.createName();
            name.setNameName("FromB");
            name.setRefersToFormula("B!$A$1");
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void keepsTheSheetAndExtraSheetsAndFreezesCrossSheetFormulas() throws Exception {
        byte[] kept = XlsxExporter.keepOnly(book(), "A");
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(kept))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(2);
            assertThat(wb.getSheetName(0)).isEqualTo("A");
            assertThat(wb.getSheetName(1)).isEqualTo(XlsxExporter.HISTORY_SHEET);
            var cross = wb.getSheet("A").getRow(0).getCell(0);
            assertThat(cross.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(cross.getNumericCellValue()).isEqualTo(84.0);
            // 같은 시트 안 수식은 그대로 둔다
            assertThat(wb.getSheet("A").getRow(0).getCell(2).getCellType()).isEqualTo(CellType.FORMULA);
            assertThat(wb.getName("FromB")).isNull();
        }
    }

    @Test
    void outputNameCarriesASafeSheetName() {
        assertThat(com.gametrans.export.ExportService.outputName("Words.xlsx", "Item", 1)).isEqualTo("Words_Item_1차수정본.xlsx");
        assertThat(com.gametrans.export.ExportService.outputName("Words.xlsx", "a/b:c*?", 2)).isEqualTo("Words_a_b_c___2차수정본.xlsx");
        assertThat(com.gametrans.export.ExportService.outputName("Words.xlsx", null, 3)).isEqualTo("Words_3차수정본.xlsx");
    }
}
