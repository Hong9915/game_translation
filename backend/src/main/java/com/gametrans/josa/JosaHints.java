package com.gametrans.josa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** segment.josa_hint(jsonb) 읽기·쓰기와 제안 끼워 넣기. */
public final class JosaHints {
    private JosaHints() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<JosaHint>> LIST = new TypeReference<>() {};

    public static List<JosaHint> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return MAPPER.readValue(json, LIST); }
        catch (JsonProcessingException e) { throw new IllegalStateException("josa_hint를 읽지 못했습니다: " + json, e); }
    }

    public static String format(List<JosaHint> hints) {
        try { return MAPPER.writeValueAsString(hints); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    /** kinds에 든 제안만 뒤에서부터 끼워 넣는다(앞 위치가 밀리지 않게). 자리가 이미 다르면 그 제안은 건너뛴다. */
    public static String applyTo(String text, List<JosaHint> hints, Set<String> kinds) {
        String out = text;
        for (JosaHint h : hints.stream().sorted(Comparator.comparingInt(JosaHint::at).reversed()).toList()) {
            if (!kinds.contains(h.kind())) continue;
            if (h.at() < 0 || !out.startsWith(h.from(), h.at())) continue;
            out = out.substring(0, h.at()) + h.to() + out.substring(h.at() + h.from().length());
        }
        return out;
    }

    /** 확인 창 예시 문구. 숫자·태그 뒤는 조사만 오므로 태그를 걷은 앞 글자 하나를 붙인다(3로 → 3으로). */
    public static String label(String text, JosaHint h) {
        if ("HANGUL".equals(h.kind())) return h.from() + " → " + h.to();
        String visible = text.substring(0, Math.max(0, Math.min(h.at(), text.length()))).replaceAll("<[^>]+>", "");
        String ctx = visible.isEmpty() ? "" : visible.substring(visible.length() - 1);
        return ctx + h.from() + " → " + ctx + h.to();
    }
}
