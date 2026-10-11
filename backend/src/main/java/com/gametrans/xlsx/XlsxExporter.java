package com.gametrans.xlsx;

import com.gametrans.normalize.NormalizeConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Component;

@Component
public class XlsxExporter {
    public static final String FLAG_HEADER = "UI_FLAG";
    public static final String FLAG_VALUE = "UI 깨짐 주의";
    public static final String HISTORY_SHEET = "수정 이력";
    public static final String GLOSSARY_SHEET = "용어집";
    public static final String NOTE_HEADER = "비고";
    /** 기존 한국어와 달라진 칸의 채우기 색(연노랑). */
    public static final String CHANGED_FILL_RGB = "FFF2CC";

    /**
     * pairIndex는 그 시트 설정의 pairs 안 번호다. 행 번호는 시트마다 겹치므로 시트 이름과 함께 봐야 한다.
     * changed: 기존 한국어와 다른 칸 — 내보낼 때 연노랑으로 채운다.
     */
    public record ExportRow(String sheetName, int rowNo, int pairIndex, String ko, boolean flagged, boolean changed) {}
    public record HistoryRow(String indexLabel, int version, String author, String text, String reviewerName) {}
    public record GlossaryRow(String src, String type, String ko) {}
    /** 검수 비고. 한 엑셀 행에 열 쌍이 여럿이면 pairIndex 순으로 " / "로 잇는다. */
    public record NoteRow(String sheetName, int rowNo, int pairIndex, String note) {}
    public record Plan(boolean addFlagColumn, boolean addHistorySheet, boolean addGlossarySheet) {}

    /**
     * 미리보기와 쓰기가 공유하는 열 배치. koIdxs는 열 쌍 순서대로의 한국어 열 위치, flagIdx는 플래그 열 위치.
     * 워크북 한 번 열기로 다 읽는다. 큰 파일은 여는 데만 수 초가 든다.
     */
    public record Layout(List<Integer> koIdxs, int flagIdx, int sheetCount, List<String> headers, List<WorkbookInspector.SheetRow> rows) {}

    public Layout layout(byte[] original, NormalizeConfig cfg) {
        try (Workbook wb = WorkbookInspector.open(original)) {
            Sheet sheet = WorkbookInspector.requireSheet(wb, cfg.getSheetName());
            Columns cols = resolve(sheet, cfg);
            return new Layout(cols.koIdxs, cols.flagIdx, wb.getNumberOfSheets(),
                    WorkbookInspector.headerOf(sheet, cfg.getHeaderRow()), WorkbookInspector.rowsOf(sheet, cfg.getHeaderRow()));
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /**
     * 설정된 시트를 모두 채워 파일 하나로 낸다. 시트마다 자기 cfg로 한국어 열·플래그 열을 따로 계산하고,
     * 행은 (시트, 행 번호)로 찾는다 — 행 번호는 시트끼리 겹치기 때문이다. 설정에 없는 시트는 손대지 않는다.
     */
    public byte[] write(byte[] original, List<NormalizeConfig> cfgs, List<ExportRow> rows, Plan plan,
                        List<HistoryRow> history, List<GlossaryRow> glossary) {
        return write(original, cfgs, rows, plan, history, glossary, List.of());
    }

    /** 행마다 비고를 " / "로 이은 글(행 번호 → 글). 시트 하나 몫. */
    public static java.util.Map<Integer, String> notesByRow(List<NoteRow> notes, String sheetName) {
        java.util.Map<Integer, java.util.TreeMap<Integer, String>> byRow = new java.util.TreeMap<>();
        for (NoteRow n : notes) {
            if (!sheetName.equals(n.sheetName()) || n.note() == null || n.note().isBlank()) continue;
            byRow.computeIfAbsent(n.rowNo(), k -> new java.util.TreeMap<>()).put(n.pairIndex(), n.note());
        }
        java.util.Map<Integer, String> out = new java.util.LinkedHashMap<>();
        byRow.forEach((row, pairs) -> out.put(row, String.join(" / ", pairs.values())));
        return out;
    }

    /**
     * notes: 검수 비고. 비고가 하나라도 있는 시트에만 마지막 열(플래그 열을 넣으면 그 다음)에 "비고" 열을 만든다.
     * 한국어가 비어 쓰지 않는 행이라도 비고는 쓴다.
     */
    public byte[] write(byte[] original, List<NormalizeConfig> cfgs, List<ExportRow> rows, Plan plan,
                        List<HistoryRow> history, List<GlossaryRow> glossary, List<NoteRow> notes) {
        try (Workbook wb = WorkbookInspector.open(original); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            java.util.Map<String, List<ExportRow>> bySheet = new java.util.HashMap<>();
            for (ExportRow r : rows) bySheet.computeIfAbsent(r.sheetName(), k -> new java.util.ArrayList<>()).add(r);
            Highlighter highlight = new Highlighter(wb);
            for (NormalizeConfig cfg : cfgs) {
                Sheet sheet = WorkbookInspector.requireSheet(wb, cfg.getSheetName());
                Columns cols = resolve(sheet, cfg);
                int flagIdx = plan.addFlagColumn() ? cols.flagIdx : -1;

                Row header = rowAt(sheet, cfg.getHeaderRow() - 1);
                for (int i = 0; i < cfg.getPairs().size(); i++) {
                    NormalizeConfig.ColumnPair pair = cfg.getPairs().get(i);
                    if (pair.isNew()) cellAt(header, cols.koIdxs.get(i)).setCellValue(pair.headerOrDefault());
                }
                if (flagIdx >= 0) cellAt(header, flagIdx).setCellValue(FLAG_HEADER);

                for (ExportRow r : bySheet.getOrDefault(cfg.getSheetName(), List.of())) {
                    Row row = rowAt(sheet, r.rowNo() - 1);
                    Cell ko = cellAt(row, cols.koIdxs.get(r.pairIndex()));
                    ko.setCellValue(r.ko());
                    if (r.changed()) highlight.apply(ko);
                    if (flagIdx >= 0 && r.flagged()) cellAt(row, flagIdx).setCellValue(FLAG_VALUE);
                }

                java.util.Map<Integer, String> sheetNotes = notesByRow(notes, cfg.getSheetName());
                if (!sheetNotes.isEmpty()) {
                    int noteIdx = plan.addFlagColumn() ? cols.flagIdx + 1 : cols.flagIdx;
                    cellAt(header, noteIdx).setCellValue(NOTE_HEADER);
                    sheetNotes.forEach((rowNo, text) -> cellAt(rowAt(sheet, rowNo - 1), noteIdx).setCellValue(text));
                }
            }

            if (plan.addHistorySheet()) {
                Sheet h = freshSheet(wb, HISTORY_SHEET);
                writeRow(h, 0, "인덱스", "버전", "작성자", "텍스트", "수정자");
                int i = 1;
                for (HistoryRow hr : history) writeRow(h, i++, hr.indexLabel(), String.valueOf(hr.version()), hr.author(), hr.text(), hr.reviewerName());
            }
            if (plan.addGlossarySheet()) {
                Sheet g = freshSheet(wb, GLOSSARY_SHEET);
                writeRow(g, 0, "원문", "유형", "한국어");
                int i = 1;
                for (GlossaryRow gr : glossary) writeRow(g, i++, gr.src(), gr.type(), gr.ko());
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private record Columns(List<Integer> koIdxs, int flagIdx) {}

    /**
     * 달라진 칸에 연노랑 채우기를 얹는다. 칸의 원래 스타일(글꼴·테두리·줄바꿈)은 지키려고 복제본에 채우기만 바꾸고,
     * 같은 원래 스타일에서 나온 복제본은 하나를 같이 쓴다 — 칸마다 스타일을 만들면 xlsx 스타일 수 한계(6만 4천)에 닿는다.
     */
    private static final class Highlighter {
        private final Workbook wb;
        private final java.util.Map<Short, CellStyle> byOriginal = new java.util.HashMap<>();

        Highlighter(Workbook wb) { this.wb = wb; }

        void apply(Cell cell) {
            CellStyle original = cell.getCellStyle();
            cell.setCellStyle(byOriginal.computeIfAbsent(original.getIndex(), k -> {
                CellStyle copy = wb.createCellStyle();
                copy.cloneStyleFrom(original);
                copy.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                if (copy instanceof org.apache.poi.xssf.usermodel.XSSFCellStyle x)
                    x.setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(hex(CHANGED_FILL_RGB), null));
                else copy.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.LIGHT_YELLOW.getIndex());
                return copy;
            }));
        }

        private static byte[] hex(String rgb) {
            byte[] out = new byte[3];
            for (int i = 0; i < 3; i++) out[i] = (byte) Integer.parseInt(rgb.substring(i * 2, i * 2 + 2), 16);
            return out;
        }
    }

    /** 기존 열 모드는 지정 열, 새 열 모드는 데이터가 있는 마지막 열 다음부터 쌍 순서대로. 플래그 열은 그 모두의 뒤. */
    static Columns resolve(Sheet sheet, NormalizeConfig cfg) {
        int next = nextFreeColumn(sheet);
        List<Integer> ko = new java.util.ArrayList<>();
        for (NormalizeConfig.ColumnPair pair : cfg.getPairs()) {
            if (pair.isNew()) ko.add(next++);
            else ko.add(CellReference.convertColStringToIndex(pair.koCol()));
        }
        int flag = next;
        for (int idx : ko) flag = Math.max(flag, idx + 1);
        return new Columns(ko, flag);
    }

    /** 모든 행을 통틀어 데이터가 있는 마지막 열 다음 인덱스. */
    static int nextFreeColumn(Sheet sheet) {
        int max = 0;
        for (Row row : sheet) max = Math.max(max, row.getLastCellNum());
        return max;
    }

    private static Sheet freshSheet(Workbook wb, String name) {
        int existing = wb.getSheetIndex(name);
        if (existing >= 0) wb.removeSheetAt(existing);
        return wb.createSheet(name);
    }

    private static void writeRow(Sheet sheet, int rowIdx, String... values) {
        Row row = rowAt(sheet, rowIdx);
        for (int i = 0; i < values.length; i++) cellAt(row, i).setCellValue(values[i]);
    }

    private static Row rowAt(Sheet sheet, int idx) {
        Row row = sheet.getRow(idx);
        return row == null ? sheet.createRow(idx) : row;
    }

    private static Cell cellAt(Row row, int idx) {
        Cell cell = row.getCell(idx);
        return cell == null ? row.createCell(idx) : cell;
    }
}
