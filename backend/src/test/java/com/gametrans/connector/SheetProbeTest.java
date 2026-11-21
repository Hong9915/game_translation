package com.gametrans.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.gametrans.connector.ConnectorDtos.ColumnStat;
import com.gametrans.text.Simplifier;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class SheetProbeTest {
    static final Simplifier SIMPLIFIER = new Simplifier();

    static void put(Sheet s, int r, int c, String v) {
        Row row = s.getRow(r) == null ? s.createRow(r) : s.getRow(r);
        row.createCell(c).setCellValue(v);
    }

    @Test
    void columnsCountContentKinds() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("S");
            String[] a = {"骑士", "騎士", "龙", "剑"};
            String[] b = {"기사", "용", "검", "방패"};
            for (int r = 0; r < 4; r++) { put(s, r, 0, a[r]); put(s, r, 1, b[r]); }
            put(s, 0, 2, "Knight");
            put(s, 1, 2, "Dragon");
            List<ColumnStat> cols = SheetProbe.columns(s, SIMPLIFIER);
            assertThat(cols).extracting(ColumnStat::letter).containsExactly("A", "B", "C");
            assertThat(cols.get(0)).isEqualTo(new ColumnStat("A", 100, 100, 0, 0, 25));
            assertThat(cols.get(1)).isEqualTo(new ColumnStat("B", 100, 0, 100, 0, 0));
            assertThat(cols.get(2)).isEqualTo(new ColumnStat("C", 50, 0, 0, 100, 0));
        }
    }

    @Test
    void topKeepsSixRowsAndCutsLongCells() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("S");
            for (int r = 0; r < 9; r++) put(s, r, 0, "행" + r);
            put(s, 0, 1, "a".repeat(41));
            put(s, 1, 1, "b".repeat(40));
            put(s, 2, 1, "  c  ");
            List<List<String>> top = SheetProbe.top(s);
            assertThat(top).hasSize(6);
            assertThat(top.get(0)).containsExactly("행0", "a".repeat(40) + "…");
            assertThat(top.get(1).get(1)).isEqualTo("b".repeat(40));
            assertThat(top.get(2).get(1)).isEqualTo("c");
        }
    }

    @Test
    void emptySheetHasNothing() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("S");
            assertThat(SheetProbe.top(s)).isEmpty();
            assertThat(SheetProbe.columns(s, SIMPLIFIER)).isEmpty();
        }
    }

    @Test
    void columnWithNoFilledCellsIsAllZero() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("S");
            put(s, 0, 0, "x");
            put(s, 1, 0, "  ");
            assertThat(SheetProbe.column(s, 1, SIMPLIFIER)).isEqualTo(new ColumnStat("B", 0, 0, 0, 0, 0));
            assertThat(SheetProbe.column(s, 0, SIMPLIFIER)).isEqualTo(new ColumnStat("A", 50, 0, 0, 100, 0));
        }
    }
}
