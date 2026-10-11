package com.gametrans.xlsx;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvWorkbookTest {
    WorkbookInspector inspector = new WorkbookInspector();

    @Test
    void parsesQuotesEmbeddedNewlinesAndBom() {
        String csv = "﻿ID,CN,EN\r\n"
                + "1,\"你好，\"\"世界\"\"\",Hello\r\n"
                + "2,\"두 줄\n텍스트\",\"a,b\"\r\n"
                + "3,,\r\n";
        byte[] xlsx = CsvWorkbook.toXlsx(csv.getBytes(StandardCharsets.UTF_8), "words.csv");

        assertThat(inspector.inspect(xlsx).sheets()).extracting(WorkbookInspector.SheetInfo::name).containsExactly("words");
        assertThat(inspector.headerCells(xlsx, "words", 1)).containsExactly("ID", "CN", "EN");
        List<WorkbookInspector.SheetRow> rows = inspector.readRows(xlsx, "words", 1);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).cells()).containsExactly("1", "你好，\"世界\"", "Hello");
        assertThat(rows.get(1).cells()).containsExactly("2", "두 줄\n텍스트", "a,b");
        assertThat(rows.get(2).cells()).containsExactly("3", "", "");
    }

    @Test
    void detectsTabDelimiterAndKoreanWindowsEncoding() {
        byte[] tsv = "ID\tCN\n1\t한글\n".getBytes(Charset.forName("MS949"));
        byte[] xlsx = CsvWorkbook.toXlsx(tsv, "a:b*c?.tsv");
        assertThat(inspector.headerCells(xlsx, "abc", 1)).containsExactly("ID", "CN");
        assertThat(inspector.readRows(xlsx, "abc", 1).get(0).cells()).containsExactly("1", "한글");
    }

    @Test
    void sheetNameIsTruncatedToExcelLimit() {
        byte[] xlsx = CsvWorkbook.toXlsx("a\n".getBytes(StandardCharsets.UTF_8), "x".repeat(40) + ".csv");
        assertThat(inspector.inspect(xlsx).sheets().get(0).name()).hasSize(31);
    }

    @Test
    void toCsvRoundTripsWithBomAndQuoting() {
        // 셀 안 줄바꿈(\n)은 그대로 두고, 레코드 구분만 CRLF로 쓴다
        String csv = "ID,CN,KO\r\n1,\"你好，\"\"世界\"\"\",\"안녕,\n세계\"\r\n";
        byte[] xlsx = CsvWorkbook.toXlsx(csv.getBytes(StandardCharsets.UTF_8), "t.csv");
        byte[] back = CsvWorkbook.toCsv(xlsx, "t");
        assertThat(new String(back, StandardCharsets.UTF_8)).isEqualTo("\uFEFF" + csv);
    }
}
