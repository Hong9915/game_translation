package com.gametrans.xlsx;

import static org.assertj.core.api.Assertions.assertThat;

import com.gametrans.TestWorkbooks;
import com.gametrans.normalize.NormalizeConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class XlsxExporterTest {
    XlsxExporter exporter = new XlsxExporter();

    private static NormalizeConfig.ColumnPair newPair(String src, String header) { return new NormalizeConfig.ColumnPair(src, "D", NormalizeConfig.KoColMode.NEW, null, header); }
    private static NormalizeConfig.ColumnPair existingPair(String src, String ko) { return new NormalizeConfig.ColumnPair(src, null, NormalizeConfig.KoColMode.EXISTING, ko, null); }

    private NormalizeConfig cfg(NormalizeConfig.ColumnPair... pairs) {
        NormalizeConfig c = new NormalizeConfig();
        c.setSheetName("ItemText");
        c.setHeaderRow(1);
        c.setKeyCol("A");
        c.setPairs(pairs.length == 0 ? List.of(newPair("C", "KO")) : List.of(pairs));
        return c;
    }

    @Test
    void writesKoAndFlagColumnsWithoutTouchingOtherCells() throws Exception {
        byte[] out = exporter.write(TestWorkbooks.sample(), List.of(cfg()),
                List.of(new XlsxExporter.ExportRow("ItemText", 2, 0, "화염의 검", true, false), new XlsxExporter.ExportRow("ItemText", 3, 0, "화염 장검", false, false)),
                new XlsxExporter.Plan(true, true, false),
                List.of(new XlsxExporter.HistoryRow("ItemText!2", 0, "LLM", "화염의 검", null)),
                List.of());
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out))) {
            Sheet s = wb.getSheet("ItemText");
            assertThat(s.getRow(0).getCell(4).getStringCellValue()).isEqualTo("KO");
            assertThat(s.getRow(0).getCell(5).getStringCellValue()).isEqualTo("UI_FLAG");
            assertThat(s.getRow(1).getCell(4).getStringCellValue()).isEqualTo("화염의 검");
            assertThat(s.getRow(1).getCell(5).getStringCellValue()).isEqualTo("UI 깨짐 주의");
            assertThat(s.getRow(2).getCell(4).getStringCellValue()).isEqualTo("화염 장검");
            assertThat(s.getRow(2).getCell(5)).isNull();
            assertThat(s.getRow(1).getCell(2).getStringCellValue()).isEqualTo("烈焰之剑");

            Sheet other = wb.getSheet("Other");
            assertThat(wb.getFontAt(other.getRow(0).getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();
            assertThat(other.getNumMergedRegions()).isEqualTo(1);

            Sheet history = wb.getSheet("수정 이력");
            assertThat(history).isNotNull();
            assertThat(history.getRow(0).getCell(4).getStringCellValue()).isEqualTo("수정자");
            assertThat(history.getRow(1).getCell(0).getStringCellValue()).isEqualTo("ItemText!2");
            org.apache.poi.ss.usermodel.Cell reviewerCell = history.getRow(1).getCell(4);
            assertThat(reviewerCell == null || reviewerCell.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK).isTrue();
            assertThat(wb.getSheet("단어장")).isNull();
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
        }
    }

    @Test
    void existingColumnModeWritesIntoGivenColumn() throws Exception {
        NormalizeConfig c = cfg(existingPair("C", "D"));
        byte[] out = exporter.write(TestWorkbooks.sample(), List.of(c), List.of(new XlsxExporter.ExportRow("ItemText", 2, 0, "화염의 검", false, false)),
                new XlsxExporter.Plan(false, false, true), List.of(), List.of(new XlsxExporter.GlossaryRow("烈焰之剑", "ITEM", "화염의 검")));
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out))) {
            assertThat(wb.getSheet("ItemText").getRow(1).getCell(3).getStringCellValue()).isEqualTo("화염의 검");
            assertThat(wb.getSheet("ItemText").getRow(0).getCell(4)).isNull();
            assertThat(wb.getSheet("단어장").getRow(1).getCell(2).getStringCellValue()).isEqualTo("화염의 검");
        }
        assertThat(exporter.layout(TestWorkbooks.sample(), c).koIdxs()).containsExactly(3);
        assertThat(exporter.layout(TestWorkbooks.sample(), cfg()).koIdxs()).containsExactly(4);
    }

    @Test
    void existingColumnModeWithFlagPlacesFlagAfterNextFreeColumn() throws Exception {
        NormalizeConfig c = cfg(existingPair("C", "D"));
        byte[] out = exporter.write(TestWorkbooks.sample(), List.of(c), List.of(new XlsxExporter.ExportRow("ItemText", 2, 0, "화염의 검", true, false)),
                new XlsxExporter.Plan(true, false, false), List.of(), List.of());
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out))) {
            Sheet sheet = wb.getSheet("ItemText");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("화염의 검");
            assertThat(sheet.getRow(0).getCell(3).getStringCellValue()).isEqualTo("EN_Text");
            assertThat(sheet.getRow(0).getCell(4).getStringCellValue()).isEqualTo("UI_FLAG");
            assertThat(sheet.getRow(1).getCell(4).getStringCellValue()).isEqualTo("UI 깨짐 주의");
        }
        assertThat(exporter.layout(TestWorkbooks.sample(), c).flagIdx()).isEqualTo(4);
    }

    @Test
    void flagColumnAccountsForDataRowsWiderThanHeaderRow() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("ItemText");
            Row header = s.createRow(0);
            header.createCell(0).setCellValue("ItemID");
            Row wide = s.createRow(1);
            wide.createCell(6).setCellValue("extra");
            wb.write(out);
            bytes = out.toByteArray();
        }
        NormalizeConfig c = cfg();
        assertThat(exporter.layout(bytes, c).koIdxs()).containsExactly(7);
        assertThat(exporter.layout(bytes, c).flagIdx()).isEqualTo(8);
    }

    /** 열 쌍이 둘이면 각자의 한국어 열에 쓰고 파일은 하나다. 새 열은 맨 끝에 순서대로, 플래그 열은 그 뒤. */
    @Test
    void twoPairsWriteIntoTheirOwnColumnsInOneFile() throws Exception {
        NormalizeConfig c = cfg(existingPair("B", "D"), newPair("C", "CN_Text_KR"));
        byte[] out = exporter.write(TestWorkbooks.sample(), List.of(c),
                List.of(new XlsxExporter.ExportRow("ItemText", 2, 0, "무기", false, false), new XlsxExporter.ExportRow("ItemText", 2, 1, "화염의 검", true, false)),
                new XlsxExporter.Plan(true, false, false), List.of(), List.of());
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out))) {
            Sheet s = wb.getSheet("ItemText");
            assertThat(s.getRow(1).getCell(3).getStringCellValue()).isEqualTo("무기");        // D (기존 열)
            assertThat(s.getRow(0).getCell(4).getStringCellValue()).isEqualTo("CN_Text_KR"); // E (새 열 헤더)
            assertThat(s.getRow(1).getCell(4).getStringCellValue()).isEqualTo("화염의 검");
            assertThat(s.getRow(0).getCell(5).getStringCellValue()).isEqualTo("UI_FLAG");
            assertThat(s.getRow(1).getCell(5).getStringCellValue()).isEqualTo("UI 깨짐 주의");
        }
        XlsxExporter.Layout layout = exporter.layout(TestWorkbooks.sample(), cfg(newPair("B", "K1"), newPair("C", "K2")));
        assertThat(layout.koIdxs()).containsExactly(4, 5);
        assertThat(layout.flagIdx()).isEqualTo(6);
    }

    /** 기존 한국어와 달라진 칸(changed)만 연노랑으로 채운다. 같은 칸은 채우기가 없다. */
    @Test
    void changedCellsGetAHighlightFillAndUnchangedOnesDoNot() throws Exception {
        NormalizeConfig c = cfg(existingPair("C", "D"));
        byte[] out = exporter.write(TestWorkbooks.sample(), List.of(c),
                List.of(new XlsxExporter.ExportRow("ItemText", 2, 0, "화염의 검", false, true),
                        new XlsxExporter.ExportRow("ItemText", 3, 0, "설명 그대로", false, false)),
                new XlsxExporter.Plan(false, false, false), List.of(), List.of());
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out))) {
            Sheet s = wb.getSheet("ItemText");
            XSSFCellStyle changed = (XSSFCellStyle) s.getRow(1).getCell(3).getCellStyle();
            assertThat(changed.getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);
            assertThat(changed.getFillForegroundXSSFColor().getARGBHex()).isEqualTo("FF" + XlsxExporter.CHANGED_FILL_RGB);
            assertThat(s.getRow(2).getCell(3).getCellStyle().getFillPattern()).isEqualTo(FillPatternType.NO_FILL);
            assertThat(s.getRow(0).getCell(3).getCellStyle().getFillPattern()).isEqualTo(FillPatternType.NO_FILL);
        }
    }

    /** 칸에 원래 스타일(굵게·줄바꿈)이 있으면 채우기만 얹고 나머지는 지킨다. 같은 원본 스타일은 복제본 하나를 같이 쓴다. */
    @Test
    void highlightKeepsTheCellsOriginalStyle() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("ItemText");
            org.apache.poi.ss.usermodel.Font bold = wb.createFont();
            bold.setBold(true);
            org.apache.poi.ss.usermodel.CellStyle style = wb.createCellStyle();
            style.setFont(bold);
            style.setWrapText(true);
            Row header = s.createRow(0);
            header.createCell(0).setCellValue("ItemID"); header.createCell(2).setCellValue("CN"); header.createCell(3).setCellValue("KR");
            for (int r = 1; r <= 2; r++) {
                Row row = s.createRow(r);
                row.createCell(0).setCellValue("IT_" + r); row.createCell(2).setCellValue("原文" + r);
                org.apache.poi.ss.usermodel.Cell ko = row.createCell(3); ko.setCellValue("옛 번역" + r); ko.setCellStyle(style);
            }
            wb.write(out);
            bytes = out.toByteArray();
        }
        byte[] out = exporter.write(bytes, List.of(cfg(existingPair("C", "D"))),
                List.of(new XlsxExporter.ExportRow("ItemText", 2, 0, "새 번역1", false, true),
                        new XlsxExporter.ExportRow("ItemText", 3, 0, "새 번역2", false, true)),
                new XlsxExporter.Plan(false, false, false), List.of(), List.of());
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out))) {
            Sheet s = wb.getSheet("ItemText");
            XSSFCellStyle a = (XSSFCellStyle) s.getRow(1).getCell(3).getCellStyle();
            XSSFCellStyle b = (XSSFCellStyle) s.getRow(2).getCell(3).getCellStyle();
            assertThat(a.getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);
            assertThat(a.getFont().getBold()).isTrue();
            assertThat(a.getWrapText()).isTrue();
            assertThat(b.getIndex()).isEqualTo(a.getIndex());
            assertThat(wb.getNumCellStyles()).isEqualTo(3);   // 기본 + 원래 스타일 + 색칠 복제본
        }
    }
}
