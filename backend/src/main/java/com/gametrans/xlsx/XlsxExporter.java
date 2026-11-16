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
    public static final String GLOSSARY_SHEET = "단어장";
    public static final String NOTE_HEADER = "비고";
    /** 기존 한국어와 달라진 칸의 채우기 색(연노랑). */
    public static final String CHANGED_FILL_RGB = "FFF2CC";
    /** 최종 리뷰 시트에서 "최종 수정" 표시를 켠 칸의 채우기 색(연두, 설계 2026-10-17). */
    public static final String FINAL_MARK_FILL_RGB = "E2F0D9";

    /**
     * pairIndex는 그 시트 설정의 pairs 안 번호다. 행 번호는 시트마다 겹치므로 시트 이름과 함께 봐야 한다.
     * changed: 기존 한국어와 다른 칸 — 내보낼 때 연노랑으로 채운다.
     * marked: 최종 리뷰 시트에서 최종 수정 표시를 켠 칸 — 연두로 채운다(changed보다 앞선다).
     */
    public record ExportRow(String sheetName, int rowNo, int pairIndex, String ko, boolean flagged, boolean changed, boolean marked) {
        public ExportRow(String sheetName, int rowNo, int pairIndex, String ko, boolean flagged, boolean changed) {
            this(sheetName, rowNo, pairIndex, ko, flagged, changed, false);
        }
    }
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
            Highlighter highlight = new Highlighter(wb, CHANGED_FILL_RGB);
            Highlighter markFill = new Highlighter(wb, FINAL_MARK_FILL_RGB);
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
                    if (r.marked()) markFill.apply(ko);
                    else if (r.changed()) highlight.apply(ko);
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

    /**
     * 시트 하나만 남긴다(설계 2026-10-12 §4). keep과 우리가 붙이는 수정 이력·단어장 시트만 두고 나머지 원본 시트는 지운다.
     * 지울 시트를 가리키는 keep의 수식은 지우기 전에 계산해 값으로 바꾸고(안 그러면 #REF!), 지울 시트를 가리키는 이름 정의도 지운다.
     * 서식·열 너비·고정 틀은 시트에 딸려 있어 그대로 남는다.
     */
    public static byte[] keepOnly(byte[] book, String keep) {
        try (Workbook wb = WorkbookInspector.open(book); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            WorkbookInspector.requireSheet(wb, keep);
            if (!keepSheets(wb, java.util.Set.of(keep, HISTORY_SHEET, GLOSSARY_SHEET))) return book;
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /**
     * 최종 수정분만(2026-10-19). headerRows의 시트만 남기고, 시트마다 머리글 줄까지와 keep 행(1부터 센 행 번호)만 두어
     * 순서대로 위로 붙인다 — 빈 줄을 남기지 않으므로 엑셀 행 번호는 원본과 달라지고 ID 열로 맞춘다.
     * 이어진 행 묶음마다 한 번씩만 옮긴다. 행 하나씩 shiftRows하면 큰 시트에서 행 수의 제곱만큼 든다.
     */
    public static byte[] keepRows(byte[] book, java.util.Map<String, Integer> headerRows, java.util.Map<String, java.util.Set<Integer>> keep) {
        try (Workbook wb = WorkbookInspector.open(book); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            keepSheets(wb, headerRows.keySet());
            for (java.util.Map.Entry<String, Integer> e : headerRows.entrySet()) {
                Sheet sheet = WorkbookInspector.requireSheet(wb, e.getKey());
                int first = e.getValue();   // 0부터 센 첫 데이터 행 = 1부터 센 머리글 줄
                java.util.TreeSet<Integer> kept = new java.util.TreeSet<>();
                for (int rowNo : keep.getOrDefault(e.getKey(), java.util.Set.of())) if (rowNo - 1 >= first) kept.add(rowNo - 1);
                for (int i = sheet.getLastRowNum(); i >= first; i--) {
                    Row row = sheet.getRow(i);
                    if (row != null && !kept.contains(i)) sheet.removeRow(row);
                }
                int target = first;
                Integer[] idx = kept.toArray(new Integer[0]);
                for (int i = 0; i < idx.length; ) {
                    int start = idx[i], end = start;
                    while (i + 1 < idx.length && idx[i + 1] == end + 1) { end++; i++; }
                    i++;
                    if (start != target) sheet.shiftRows(start, end, target - start, true, false);
                    target += end - start + 1;
                }
            }
            wb.setActiveSheet(0);
            wb.setSelectedTab(0);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /**
     * kept에 없는 시트를 지운다. 남는 시트의 수식 가운데 지울 시트를 가리키는 것은 지우기 전에 값으로 바꾸고(안 그러면 #REF!),
     * 지울 시트를 가리키는 이름 정의도 지운다. 지울 시트가 없으면 아무것도 하지 않고 false.
     */
    private static boolean keepSheets(Workbook wb, java.util.Set<String> kept) {
        java.util.Set<String> gone = new java.util.HashSet<>();
        for (int i = 0; i < wb.getNumberOfSheets(); i++) if (!kept.contains(wb.getSheetName(i))) gone.add(wb.getSheetName(i));
        if (gone.isEmpty()) return false;
        org.apache.poi.ss.usermodel.FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            if (gone.contains(wb.getSheetName(i))) continue;
            for (Row row : wb.getSheetAt(i)) for (Cell cell : row) {
                if (cell.getCellType() != org.apache.poi.ss.usermodel.CellType.FORMULA || !refersTo(cell.getCellFormula(), gone)) continue;
                freeze(cell, eval);
            }
        }
        for (int i = wb.getNumberOfNames() - 1; i >= 0; i--) {
            org.apache.poi.ss.usermodel.Name n = wb.getAllNames().get(i);
            if (n.getSheetIndex() >= 0 && gone.contains(wb.getSheetName(n.getSheetIndex()))) continue;   // 시트 범위 이름은 시트와 같이 지워진다
            String f = n.getRefersToFormula();
            if (f != null && refersTo(f, gone)) wb.removeName(n);
        }
        for (int i = wb.getNumberOfSheets() - 1; i >= 0; i--) if (gone.contains(wb.getSheetName(i))) wb.removeSheetAt(i);
        wb.setActiveSheet(0);
        wb.setSelectedTab(0);
        return true;
    }

    /** 수식이 지울 시트 가운데 하나를 가리키는지. 시트 이름은 그대로(Item!A1) 또는 따옴표로('기타 시트'!A1) 나온다. */
    private static boolean refersTo(String formula, java.util.Set<String> sheets) {
        for (String s : sheets)
            if (formula.contains(s + "!") || formula.contains("'" + s.replace("'", "''") + "'!")) return true;
        return false;
    }

    /** 수식을 지금 값으로 바꾼다. 계산이 안 되면(외부 참조 등) 파일에 저장된 마지막 결과를 쓴다. */
    private static void freeze(Cell cell, org.apache.poi.ss.usermodel.FormulaEvaluator eval) {
        org.apache.poi.ss.usermodel.CellValue v;
        try { v = eval.evaluate(cell); } catch (RuntimeException e) { v = null; }
        org.apache.poi.ss.usermodel.CellType cached = cell.getCachedFormulaResultType();
        if (v == null) {
            switch (cached) {
                case NUMERIC -> { double d = cell.getNumericCellValue(); cell.removeFormula(); cell.setCellValue(d); }
                case BOOLEAN -> { boolean b = cell.getBooleanCellValue(); cell.removeFormula(); cell.setCellValue(b); }
                case STRING -> { String t = cell.getStringCellValue(); cell.removeFormula(); cell.setCellValue(t); }
                default -> { cell.removeFormula(); cell.setBlank(); }
            }
            return;
        }
        cell.removeFormula();
        switch (v.getCellType()) {
            case NUMERIC -> cell.setCellValue(v.getNumberValue());
            case BOOLEAN -> cell.setCellValue(v.getBooleanValue());
            case STRING -> cell.setCellValue(v.getStringValue());
            default -> cell.setBlank();
        }
    }

    private record Columns(List<Integer> koIdxs, int flagIdx) {}

    /**
     * 달라진 칸에 연노랑 채우기를 얹는다. 칸의 원래 스타일(글꼴·테두리·줄바꿈)은 지키려고 복제본에 채우기만 바꾸고,
     * 같은 원래 스타일에서 나온 복제본은 하나를 같이 쓴다 — 칸마다 스타일을 만들면 xlsx 스타일 수 한계(6만 4천)에 닿는다.
     */
    private static final class Highlighter {
        private final Workbook wb;
        private final String rgb;
        private final java.util.Map<Short, CellStyle> byOriginal = new java.util.HashMap<>();

        Highlighter(Workbook wb, String rgb) { this.wb = wb; this.rgb = rgb; }

        void apply(Cell cell) {
            CellStyle original = cell.getCellStyle();
            cell.setCellStyle(byOriginal.computeIfAbsent(original.getIndex(), k -> {
                CellStyle copy = wb.createCellStyle();
                copy.cloneStyleFrom(original);
                copy.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                if (copy instanceof org.apache.poi.xssf.usermodel.XSSFCellStyle x)
                    x.setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(hex(rgb), null));
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
            else ko.add(CellReference.convertColStringToIndex(pair.fixCol() != null ? pair.fixCol() : pair.koCol()));
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

    /**
     * 글을 쓸 칸. 글자를 칸 안에 직접 적은 칸(t="inlineStr" — openpyxl write_only·SXSSF가 만든 파일)은 비워서 돌려준다.
     * POI는 그런 칸에 setCellValue하면 &lt;v&gt;만 바꾸고 옛 글(&lt;is&gt;)을 남겨, 엑셀이 옛 글을 그대로 보여 준다. 스타일은 남는다.
     */
    private static Cell cellAt(Row row, int idx) {
        Cell cell = row.getCell(idx);
        if (cell == null) return row.createCell(idx);
        if (cell instanceof org.apache.poi.xssf.usermodel.XSSFCell x && x.getCTCell().isSetIs()) cell.setBlank();
        return cell;
    }
}
