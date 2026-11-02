package com.gametrans.glossary;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/** 개발사가 채운 "단어장 요청양식"(xlsx)을 읽어 (분류, 원문) 목록으로 만든다. 머리글 열 순서는 상관없다. */
public final class TermRequestForm {
    private TermRequestForm() {}

    /** 양식에서 읽은 용어 하나. category는 HEADERS의 값(CHARACTER, ITEM, …). */
    public record FormTerm(String category, String src) {}

    /** 머리글(중국어) → 양식 분류. */
    public static final Map<String, String> HEADERS = Map.of(
        "角色", "CHARACTER",
        "道具", "ITEM",
        "技能", "SKILL",
        "地点", "PLACE",
        "怪物", "MONSTER",
        "货币", "CURRENCY",
        "其他", "OTHER");

    /** 양식 4행에 미리 적혀 있는 예시 값. 개발사가 그대로 둔 칸은 무시한다. */
    private static final Map<String, String> EXAMPLES = Map.of(
        "角色", "森之召唤师",
        "道具", "火焰之剑",
        "技能", "雷霆一击",
        "地点", "迷雾森林",
        "怪物", "暗影狼王",
        "货币", "钻石",
        "其他", "公会");

    private static final int MAX_HEADER_ROW = 5;
    private static final String NOT_A_FORM =
        "단어장 요청양식이 아닙니다: 2행에 角色·道具·技能·地点·怪物·货币·其他 머리글이 없습니다.";

    /** 양식 분류 → 단어장(glossary_category) 분류. 몬스터는 캐릭터로, 화폐는 아이템으로 묶는다. */
    public static String glossaryCategory(String category) {
        return switch (category) {
            case "CHARACTER", "MONSTER" -> "CHARACTER";
            case "ITEM", "CURRENCY" -> "ITEM";
            case "SKILL" -> "SKILL";
            case "PLACE" -> "PLACE";
            default -> "NAME_OTHER";
        };
    }

    public static List<FormTerm> parse(byte[] xlsx) {
        try (Workbook wb = open(xlsx)) {
            if (wb.getNumberOfSheets() == 0) throw new IllegalArgumentException(NOT_A_FORM);
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();

            Row header = null;
            for (int r = 0; r < MAX_HEADER_ROW && header == null; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                for (Cell c : row) if (HEADERS.containsKey(fmt.formatCellValue(c).strip())) { header = row; break; }
            }
            if (header == null) throw new IllegalArgumentException(NOT_A_FORM);

            // 열 번호 → 머리글(중국어)
            Map<Integer, String> columns = new java.util.TreeMap<>();
            for (Cell c : header) {
                String h = fmt.formatCellValue(c).strip();
                if (HEADERS.containsKey(h)) columns.put(c.getColumnIndex(), h);
            }

            int exampleRow = header.getRowNum() + 2; // 머리글, 설명(例：) 다음 줄이 예시
            Set<String> seen = new LinkedHashSet<>();
            List<FormTerm> out = new ArrayList<>();
            for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || isDescriptionRow(row, columns, fmt)) continue;
                for (Map.Entry<Integer, String> col : columns.entrySet()) {
                    Cell cell = row.getCell(col.getKey());
                    if (cell == null) continue;
                    String v = fmt.formatCellValue(cell).strip();
                    if (v.isEmpty()) continue;
                    if (r == exampleRow && v.equals(EXAMPLES.get(col.getValue()))) continue;
                    if (seen.add(v)) out.add(new FormTerm(HEADERS.get(col.getValue()), v));
                }
            }
            return out;
        } catch (IOException e) {   // 닫기 실패뿐 — 메모리 위 바이트라 사실상 없다
            throw new UncheckedIOException(e);
        }
    }

    /** 엑셀이 아니거나 깨진 파일이면 POI가 IOException이나 여러 런타임 예외(NotOfficeXmlFileException 등)를 낸다 — 모두 "양식이 아님"으로. */
    private static Workbook open(byte[] xlsx) {
        try {
            return WorkbookFactory.create(new ByteArrayInputStream(xlsx));
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException(NOT_A_FORM, e);
        }
    }

    /** 3행 설명 줄: 용어 칸 중 하나라도 "例："로 시작한다. */
    private static boolean isDescriptionRow(Row row, Map<Integer, String> columns, DataFormatter fmt) {
        for (int col : columns.keySet()) {
            Cell c = row.getCell(col);
            if (c == null) continue;
            String v = fmt.formatCellValue(c).strip();
            if (v.startsWith("例：") || v.startsWith("例:")) return true;
        }
        return false;
    }
}
