package com.gametrans.xlsx;

import static org.assertj.core.api.Assertions.assertThat;

import com.gametrans.TestWorkbooks;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class WorkbookInspectorTest {
    WorkbookInspector inspector = new WorkbookInspector();

    @Test
    void inspectListsSheetsAndRowCounts() {
        WorkbookInspector.WorkbookInfo info = inspector.inspect(TestWorkbooks.sample());
        assertThat(info.sheets()).extracting(WorkbookInspector.SheetInfo::name).containsExactly("ItemText", "Other");
        assertThat(info.sheets().get(0).rowCount()).isEqualTo(7);
        assertThat(info.totalRows()).isEqualTo(9);
    }

    @Test
    void headerCellsReturnsRowByOneBasedNumber() {
        List<String> headers = inspector.headerCells(TestWorkbooks.sample(), "ItemText", 1);
        assertThat(headers).containsExactly("ItemID", "Type", "CN_Text", "EN_Text");
    }

    /** 시트 목록 화면용: 워크북을 한 번만 열어 모든 시트의 행 수와 헤더를 돌려준다. */
    @Test
    void sheetHeadersReadsEverySheetInOneOpen() {
        List<WorkbookInspector.SheetHeaders> all = inspector.sheetHeaders(TestWorkbooks.sample(), 1);
        assertThat(all).extracting(WorkbookInspector.SheetHeaders::name).containsExactly("ItemText", "Other");
        assertThat(all.get(0).rowCount()).isEqualTo(7);
        assertThat(all.get(0).headers()).containsExactly("ItemID", "Type", "CN_Text", "EN_Text");
        assertThat(all.get(1).headers()).containsExactly("Bold title");
        assertThat(inspector.sheetHeaders(TestWorkbooks.sample(), 99).get(0).headers()).isEmpty();
    }

    @Test
    void readRowsReturnsRowsAfterHeaderWithExcelRowNumbers() {
        List<WorkbookInspector.SheetRow> rows = inspector.readRows(TestWorkbooks.sample(), "ItemText", 1);
        assertThat(rows).hasSize(6);
        assertThat(rows.get(0).rowNo()).isEqualTo(2);
        assertThat(rows.get(0).cells()).containsExactly("IT_10001", "weapon", "烈焰之剑", "Blazing Sword");
        assertThat(rows.get(3).cells().get(2)).isEmpty();
    }

    /** 실제 게임 시트에는 POI가 계산 못 하는 FILTER/UNIQUE 같은 수식이 있다. 계산하지 말고 저장된 값을 읽어야 한다. */
    @Test
    void formulaCellsUseCachedValueInsteadOfEvaluating() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Words_main");
            Row header = sh.createRow(0);
            header.createCell(0).setCellValue("中文");
            Cell f = header.createCell(1);
            f.setCellFormula("_xlws.FILTER(A:A,A:A<>\"\")");
            f.setCellValue("查重");            // 엑셀이 저장해 둔 결과값
            Row data = sh.createRow(1);
            data.createCell(0).setCellValue("你好");
            Cell len = data.createCell(1);
            len.setCellFormula("LEN(A2)");
            len.setCellValue(2);
            Cell err = data.createCell(2);
            err.setCellFormula("_xlfn.UNIQUE(A:A)");
            err.setCellErrorValue(org.apache.poi.ss.usermodel.FormulaError.NA.getCode());
            wb.write(out);
            bytes = out.toByteArray();
        }
        assertThat(inspector.headerCells(bytes, "Words_main", 1)).containsExactly("中文", "查重");
        List<WorkbookInspector.SheetRow> rows = inspector.readRows(bytes, "Words_main", 1);
        assertThat(rows.get(0).cells()).containsExactly("你好", "2", "");
    }
}
