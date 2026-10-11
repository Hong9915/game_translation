package com.gametrans.xlsx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * CSV ↔ 단일 시트 xlsx 변환. CSV로 올린 파일도 내부에서는 xlsx로 다뤄 파이프라인을 하나로 유지한다.
 * 파싱은 RFC 4180(따옴표, 따옴표 안 줄바꿈·구분자, "" 이스케이프)을 따르고 구분자는 쉼표 또는 탭을 자동 감지한다.
 */
public final class CsvWorkbook {
    private static final String BOM = "﻿";
    private static final int SHEET_NAME_MAX = 31;

    private CsvWorkbook() {}

    public static byte[] toXlsx(byte[] csv, String fileName) {
        String text = decode(csv);
        if (text.startsWith(BOM)) text = text.substring(1);
        char delimiter = detectDelimiter(text);
        List<List<String>> records = parse(text, delimiter);
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(sheetNameFor(fileName));
            for (int r = 0; r < records.size(); r++) {
                Row row = sheet.createRow(r);
                List<String> rec = records.get(r);
                for (int c = 0; c < rec.size(); c++) row.createCell(c).setCellValue(rec.get(c));
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** 시트 하나를 UTF-8(BOM 포함, 엑셀이 한글을 바로 읽도록) CSV로 직렬화한다. 다른 시트는 버려진다. */
    public static byte[] toCsv(byte[] xlsx, String sheetName) {
        try (Workbook wb = WorkbookInspector.open(xlsx)) {
            Sheet sheet = WorkbookInspector.requireSheet(wb, sheetName);
            StringBuilder sb = new StringBuilder(BOM);
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                int last = row == null ? 0 : row.getLastCellNum();
                for (int c = 0; c < last; c++) {
                    if (c > 0) sb.append(',');
                    Cell cell = row.getCell(c);
                    sb.append(quote(cell == null ? "" : WorkbookInspector.cellText(cell)));
                }
                sb.append("\r\n");
            }
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** 확장자를 뗀 파일명에서 엑셀이 허용하지 않는 문자를 지우고 31자로 자른다. */
    public static String sheetNameFor(String fileName) {
        String base = fileName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replaceAll("[\\[\\]:*?/\\\\]", "").trim();
        if (base.isEmpty()) base = "Sheet1";
        return base.length() > SHEET_NAME_MAX ? base.substring(0, SHEET_NAME_MAX) : base;
    }

    /** UTF-8로 엄격히 디코딩해 보고, 깨지면 한국어 윈도우(MS949), 그다음 중국어(GB18030) 순으로 시도한다. */
    private static String decode(byte[] bytes) {
        for (String cs : new String[] {"UTF-8", "MS949"}) {
            try {
                return Charset.forName(cs).newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException ignored) { /* 다음 문자셋 */ }
        }
        return new String(bytes, Charset.forName("GB18030"));
    }

    private static char detectDelimiter(String text) {
        int nl = text.indexOf('\n');
        String first = nl < 0 ? text : text.substring(0, nl);
        long tabs = first.chars().filter(ch -> ch == '\t').count();
        long commas = first.chars().filter(ch -> ch == ',').count();
        return tabs > commas ? '\t' : ',';
    }

    static List<List<String>> parse(String text, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int i = 0, n = text.length();
        while (i < n) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') { field.append('"'); i += 2; continue; }
                    quoted = false;
                } else field.append(ch);
                i++;
                continue;
            }
            if (ch == '"' && field.length() == 0) quoted = true;
            else if (ch == delimiter) { record.add(field.toString()); field.setLength(0); }
            else if (ch == '\r' || ch == '\n') {
                if (ch == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                record.add(field.toString()); field.setLength(0);
                records.add(record); record = new ArrayList<>();
            } else field.append(ch);
            i++;
        }
        if (field.length() > 0 || !record.isEmpty()) { record.add(field.toString()); records.add(record); }
        return records;
    }

    private static String quote(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
