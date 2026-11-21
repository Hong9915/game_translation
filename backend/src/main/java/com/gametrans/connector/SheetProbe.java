package com.gametrans.connector;

import com.gametrans.connector.ConnectorDtos.ColumnStat;
import com.gametrans.text.Simplifier;
import com.gametrans.xlsx.WorkbookInspector;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;

/** 시트 한 장을 겉에서 들여다본다: 맨 위 몇 행과 열별 글자 종류 통계. 열 역할은 헤더 이름이 아니라 내용을 보고 고르게 한다. */
public final class SheetProbe {
    static final int TOP_ROWS = 6;
    static final int CELL_MAX = 40;
    static final int SAMPLE_ROWS = 300;

    private SheetProbe() {}

    /** 맨 위 6행(물리 행 0~5). 칸은 다듬어 40자에서 자르고, 비어 있는 중간 행은 빈 목록. 내용이 없는 시트는 []. */
    public static List<List<String>> top(Sheet sheet) {
        List<List<String>> out = new ArrayList<>();
        if (sheet.getPhysicalNumberOfRows() == 0) return out;
        int last = Math.min(TOP_ROWS - 1, sheet.getLastRowNum());
        for (int r = 0; r <= last; r++) {
            Row row = sheet.getRow(r);
            List<String> cells = new ArrayList<>();
            if (row != null) {
                for (int c = 0; c < row.getLastCellNum(); c++) cells.add(cut(text(row.getCell(c))));
            }
            out.add(cells);
        }
        return out;
    }

    /** 표본(맨 위 300행) 안의 가장 넓은 행까지의 모든 열. */
    public static List<ColumnStat> columns(Sheet sheet, Simplifier simplifier) {
        List<ColumnStat> out = new ArrayList<>();
        int width = width(sheet);
        for (int c = 0; c < width; c++) out.add(column(sheet, c, simplifier));
        return out;
    }

    public static ColumnStat column(Sheet sheet, int colIdx, Simplifier simplifier) {
        int rows = sampleRows(sheet);
        int filled = 0, han = 0, hangul = 0, latin = 0, trad = 0;
        for (int r = 0; r < rows; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String t = text(row.getCell(colIdx));
            if (t.isEmpty()) continue;
            filled++;
            switch (kind(t)) {
                case HANGUL -> hangul++;
                case HAN -> han++;
                case LATIN -> latin++;
                default -> { }
            }
            if (simplifier.hasTraditional(t)) trad++;
        }
        return new ColumnStat(CellReference.convertNumToColString(colIdx),
                pct(filled, rows), pct(han, filled), pct(hangul, filled), pct(latin, filled), pct(trad, filled));
    }

    private enum Kind { HANGUL, HAN, LATIN, OTHER }

    private static Kind kind(String t) {
        boolean han = false, latin = false;
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= 0xAC00 && cp <= 0xD7A3) return Kind.HANGUL;
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) han = true;
            else if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN && Character.isLetter(cp)) latin = true;
        }
        return han ? Kind.HAN : latin ? Kind.LATIN : Kind.OTHER;
    }

    private static int sampleRows(Sheet sheet) {
        if (sheet.getPhysicalNumberOfRows() == 0) return 0;
        return Math.min(SAMPLE_ROWS, sheet.getLastRowNum() + 1);
    }

    private static int width(Sheet sheet) {
        int rows = sampleRows(sheet), w = 0;
        for (int r = 0; r < rows; r++) {
            Row row = sheet.getRow(r);
            if (row != null) w = Math.max(w, row.getLastCellNum());
        }
        return w;
    }

    /** 표본(맨 위 300행)에서 글이 있는 칸 수. ColumnStat.filled는 %라 5칸 이상 같은 기준에는 쓸 수 없다. */
    static int filledCount(Sheet sheet, int colIdx) {
        int rows = sampleRows(sheet), n = 0;
        for (int r = 0; r < rows; r++) {
            Row row = sheet.getRow(r);
            if (row != null && !text(row.getCell(colIdx)).isEmpty()) n++;
        }
        return n;
    }

    static String text(Cell cell) { return cell == null ? "" : WorkbookInspector.cellText(cell).trim(); }

    private static String cut(String s) { return s.length() > CELL_MAX ? s.substring(0, CELL_MAX) + "…" : s; }

    /** 정수 %, 반올림(.5는 올림). 분모가 0이면 0. */
    private static int pct(int n, int of) { return of == 0 ? 0 : (int) ((n * 100L * 2 + of) / (of * 2L)); }
}
