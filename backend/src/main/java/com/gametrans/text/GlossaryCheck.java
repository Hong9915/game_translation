package com.gametrans.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 번역 결과에 확정 용어의 한국어가 들어갔는지 검사한다. 원문에 등장하는 용어만 본다. */
public final class GlossaryCheck {
    private GlossaryCheck() {}

    /**
     * 확정 번역 뒤에 붙은 라벨 메모: "골드 (의역: 게임 내 기본 화폐)". 라벨은 짧은 한글·영문 단어 하나라서
     * "상품(shopId: ${key}) 없음"처럼 문장 속 괄호나 "성(요새)"처럼 라벨 없는 괄호는 번역의 일부로 둔다.
     */
    private static final Pattern NOTE = Pattern.compile("^(.*\\S)\\s*[(（]\\s*([가-힣A-Za-z]{1,6}\\s*[:：]\\s*[^()（）]+?)\\s*[)）]\\s*$");

    /** 번역문에 실제로 들어가야 하는 부분(메모를 뗀 확정 번역). */
    public static String koOf(String confirmedKo) {
        if (confirmedKo == null) return null;
        Matcher m = NOTE.matcher(confirmedKo);
        return m.matches() ? m.group(1).strip() : confirmedKo;
    }

    /** 확정 번역 뒤의 메모("의역: 게임 내 기본 화폐"). 없으면 null. */
    public static String noteOf(String confirmedKo) {
        if (confirmedKo == null) return null;
        Matcher m = NOTE.matcher(confirmedKo);
        return m.matches() ? m.group(2).strip() : null;
    }

    /** 빠진 용어를 "원문→한국어" 형태로. 없으면 빈 목록. ko가 null이면(아직 번역 없음) 검사하지 않는다. */
    public static List<String> missing(String src, String ko, Map<String, String> glossary) {
        List<String> out = new ArrayList<>();
        if (src == null || ko == null || glossary == null || glossary.isEmpty()) return out;
        for (Map.Entry<String, String> e : applicable(src, glossary).entrySet())
            if (!ko.contains(e.getValue())) out.add(e.getKey() + "→" + e.getValue());
        return out;
    }

    /**
     * 이 원문에 실제로 적용되는 용어 (원문 → 메모를 뗀 한국어, 입력 순서 유지). 긴 용어가 먼저 자리를 차지하고, 더 긴 용어 안에만
     * 나오는 짧은 용어는 뺀다: 黑暗城堡先锋榜이 있으면 그 안의 城堡·先锋榜은 따로 요구하지 않는다.
     */
    public static Map<String, String> applicable(String src, Map<String, String> glossary) {
        Map<String, String> out = new LinkedHashMap<>();
        if (src == null || glossary == null) return out;
        List<String> present = new ArrayList<>();
        for (Map.Entry<String, String> e : glossary.entrySet()) {
            String term = e.getKey(), target = e.getValue();
            if (term == null || term.isBlank() || target == null || target.isBlank()) continue;
            if (src.contains(term)) present.add(term);
        }
        List<String> byLength = new ArrayList<>(present);
        byLength.sort(Comparator.comparingInt(String::length).reversed());   // 안정 정렬: 길이가 같으면 입력 순서
        boolean[] taken = new boolean[src.length()];
        List<String> kept = new ArrayList<>();
        for (String term : byLength) {
            boolean free = false;
            for (int at = src.indexOf(term); at >= 0; at = src.indexOf(term, at + 1)) {
                boolean covered = true;
                for (int i = at; i < at + term.length(); i++) covered &= taken[i];
                if (covered) continue;
                free = true;
                Arrays.fill(taken, at, at + term.length(), true);
            }
            if (free) kept.add(term);
        }
        for (String term : present) if (kept.contains(term)) out.put(term, koOf(glossary.get(term).trim()));
        return out;
    }

    /** 저장용: 줄 단위로 합친다. 빈 목록은 null(열을 비움). */
    public static String format(List<String> missing) {
        return missing == null || missing.isEmpty() ? null : String.join("\n", missing);
    }

    public static List<String> parse(String stored) {
        return stored == null || stored.isBlank() ? List.of() : Arrays.asList(stored.split("\n"));
    }

    /**
     * 앞의 숫자(+级·关 같은 단위 한 글자; 星은 용어의 일부라 떼지 않는다)와 뒤의 숫자. "第3关"처럼 第가 붙은 것도 앞 숫자로 본다.
     * 숫자 뒤가 한자일 때만 뗀다: 3D模型·5v5竞技场처럼 숫자가 이름의 일부인 것을 변형으로 잘못 보지 않기 위해서다.
     * 단위 한 글자는 소유 수량자(?+)라서 되돌리지 않는다 — 第3关BOSS에서 关만 남기는 식으로 쪼개지 않게.
     */
    private static final Pattern LEADING_NUMBER = Pattern.compile("^第?[0-9０-９]+[级层阶段期关号章轮]?+(?=[一-鿿])");
    private static final Pattern TRAILING_NUMBER = Pattern.compile("[0-9０-９]+$");

    /**
     * 숫자만 다른 변형 용어(11星英雄, 110级史诗装备自选宝箱, 1280钻石)의 기본 단어(星英雄, 史诗装备自选宝箱, 钻石).
     * 변형이 아니면(숫자가 없거나, 앞 숫자 뒤가 한자가 아니거나, 떼고 나면 2글자 미만이거나, 가운데 숫자) null.
     */
    public static String numericStem(String src) {
        if (src == null) return null;
        String stem = LEADING_NUMBER.matcher(src).replaceFirst("");
        stem = TRAILING_NUMBER.matcher(stem).replaceFirst("").strip();
        return stem.length() >= 2 && !stem.equals(src) ? stem : null;
    }
}
