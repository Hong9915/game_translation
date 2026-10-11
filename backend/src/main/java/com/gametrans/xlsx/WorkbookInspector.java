package com.gametrans.xlsx;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

@Component
public class WorkbookInspector {
    public record SheetInfo(String name, int rowCount) {}
    public record WorkbookInfo(List<SheetInfo> sheets) {
        public int totalRows() { return sheets.stream().mapToInt(SheetInfo::rowCount).sum(); }
    }
    public record SheetRow(int rowNo, List<String> cells) {}
    public record SheetHeaders(String name, int rowCount, List<String> headers, List<List<String>> preview) {}

    /** 화면에서 열 역할을 고를 때 보여 줄 미리보기 행 수. */
    private static final int PREVIEW_ROWS = 5;

    /** 모든 시트의 행 수와 지정 행의 헤더를 워크북 한 번 열기로 읽는다. 큰 파일은 여는 데만 수 초·수백 MB가 든다. */
    public List<SheetHeaders> sheetHeaders(byte[] bytes, int headerRow) {
        try (Workbook wb = open(bytes)) {
            List<SheetHeaders> out = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet s = wb.getSheetAt(i);
                Row row = s.getRow(headerRow - 1);
                List<List<String>> preview = new ArrayList<>();
                for (int r = headerRow; r < headerRow + PREVIEW_ROWS; r++) {
                    Row data = s.getRow(r);
                    if (data == null) continue;
                    preview.add(cellsOf(data));
                }
                out.add(new SheetHeaders(s.getSheetName(), s.getPhysicalNumberOfRows(), row == null ? List.of() : cellsOf(row), preview));
            }
            return out;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public WorkbookInfo inspect(byte[] bytes) {
        try (Workbook wb = open(bytes)) {
            List<SheetInfo> sheets = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet s = wb.getSheetAt(i);
                sheets.add(new SheetInfo(s.getSheetName(), s.getPhysicalNumberOfRows()));
            }
            return new WorkbookInfo(sheets);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public List<String> headerCells(byte[] bytes, String sheetName, int headerRow) {
        try (Workbook wb = open(bytes)) {
            Sheet s = requireSheet(wb, sheetName);
            Row row = s.getRow(headerRow - 1);
            return row == null ? List.of() : cellsOf(row);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public List<SheetRow> readRows(byte[] bytes, String sheetName, int headerRow) {
        try (Workbook wb = open(bytes)) {
            return rowsOf(requireSheet(wb, sheetName), headerRow);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** 이미 연 시트에서 헤더 다음 행들을 읽는다(워크북을 다시 열지 않음). */
    public static List<SheetRow> rowsOf(Sheet s, int headerRow) {
        List<SheetRow> out = new ArrayList<>();
        for (int r = headerRow; r <= s.getLastRowNum(); r++) {
            Row row = s.getRow(r);
            out.add(new SheetRow(r + 1, row == null ? List.of() : cellsOf(row)));
        }
        return out;
    }

    public static List<String> headerOf(Sheet s, int headerRow) {
        Row row = s.getRow(headerRow - 1);
        return row == null ? List.of() : cellsOf(row);
    }

    public static Workbook open(byte[] bytes) throws IOException {
        return WorkbookFactory.create(new ByteArrayInputStream(bytes));
    }

    public static Sheet requireSheet(Workbook wb, String name) {
        Sheet s = wb.getSheet(name);
        if (s == null) throw new IllegalArgumentException("시트를 찾을 수 없습니다: " + name);
        return s;
    }

    private static List<String> cellsOf(Row row) {
        DataFormatter fmt = new DataFormatter();
        List<String> cells = new ArrayList<>();
        int last = row.getLastCellNum();
        for (int c = 0; c < last; c++) {
            Cell cell = row.getCell(c);
            cells.add(cell == null ? "" : cellText(cell, fmt).trim());
        }
        return cells;
    }

    /**
     * 수식 셀은 계산하지 않고 엑셀이 저장해 둔 결과값을 읽는다. 게임 텍스트 시트에는
     * POI가 지원하지 않는 FILTER/UNIQUE 같은 수식이 흔해서 계산하면 예외가 난다.
     */
    public static String cellText(Cell cell) { return cellText(cell, new DataFormatter()); }

    static String cellText(Cell cell, DataFormatter fmt) {
        if (cell.getCellType() != CellType.FORMULA) return fmt.formatCellValue(cell);
        return switch (cell.getCachedFormulaResultType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> fmt.formatRawCellContents(cell.getNumericCellValue(),
                    cell.getCellStyle().getDataFormat(), cell.getCellStyle().getDataFormatString());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";  // ERROR(#N/A 등), BLANK
        };
    }
}
