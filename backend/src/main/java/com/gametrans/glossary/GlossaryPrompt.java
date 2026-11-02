package com.gametrans.glossary;

import com.gametrans.text.GlossaryCheck;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 단어장 항목을 GPT 프롬프트에 싣는 모양으로 바꾼다.
 * 메모는 예전 확정 번역처럼 "번역어 (라벨: 메모)"로 붙인다 — Prompts가 이 모양을 알아보고 번역어와 메모를 떼어 보여 주며,
 * GlossaryCheck.koOf가 메모를 떼므로 용어 누락 검사에도 번역어만 쓰인다.
 */
public final class GlossaryPrompt {
    private GlossaryPrompt() {}

    /** GlossaryCheck.NOTE가 알아보는 라벨("의역:", "메모:")로 시작하는 메모. */
    private static final Pattern LABEL = Pattern.compile("^[가-힣A-Za-z]{1,6}\\s*[:：]");

    /** 원문 → 번역어(메모가 있으면 붙여서). 유효 단어장 가운데 SRC이고 ko가 있는 항목만. */
    public static Map<String, String> srcTerms(List<GlossaryEntry> effective) {
        Map<String, String> out = new LinkedHashMap<>();
        for (GlossaryEntry e : effective) {
            if (e.getKind() != GlossaryEntry.Kind.SRC || e.getKo() == null || e.getKo().isBlank()) continue;
            out.put(e.getFind(), withNote(e.getKo(), e.getNote()));
        }
        return out;
    }

    /** 이 원문에 find가 들어 있는 메모 전용 항목(SRC, ko 없음, 메모 있음)의 메모. 단어장 순서대로. */
    public static List<String> memoNotes(List<GlossaryEntry> effective, String src) {
        List<String> out = new ArrayList<>();
        if (src == null) return out;
        for (GlossaryEntry e : effective) {
            if (e.getKind() != GlossaryEntry.Kind.SRC || (e.getKo() != null && !e.getKo().isBlank())) continue;
            if (e.getNote() == null || e.getNote().isBlank() || e.getFind() == null || !src.contains(e.getFind())) continue;
            out.add(e.getNote().strip());
        }
        return out;
    }

    /** 지시문 뒤에 메모를 줄바꿈으로 붙인다. 메모가 없으면 지시문 그대로. */
    public static String instructions(String base, List<String> notes) {
        if (notes == null || notes.isEmpty()) return base;
        String joined = String.join("\n", notes);
        return base == null || base.isBlank() ? joined : base + "\n" + joined;
    }

    /**
     * "화염" + "의역: 불꽃 속성" → "화염 (의역: 불꽃 속성)". 라벨이 없는 메모는 "메모:"를 붙인다.
     * 메모 속 괄호·줄바꿈은 메모 모양을 깨므로 바꾸고, 그래도 koOf가 번역어를 되돌려 주지 못하면 메모 없이 번역어만 쓴다.
     */
    static String withNote(String ko, String note) {
        if (note == null || note.isBlank()) return ko;
        String n = note.strip().replaceAll("\\s+", " ")
                .replace('(', '[').replace(')', ']').replace('（', '[').replace('）', ']');
        if (!LABEL.matcher(n).find()) n = "메모: " + n;
        String out = ko + " (" + n + ")";
        return ko.equals(GlossaryCheck.koOf(out)) ? out : ko;
    }
}
