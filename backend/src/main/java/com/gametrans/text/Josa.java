package com.gametrans.text;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 받침에 맞는 조사 꼴(설계 2026-10-07 §4). 단어장 적용·용어 치환이 표기를 바꿀 때 바로 뒤 조사를 새 표기 받침에 맞춘다.
 * 원래 맞게 쓰인 조사만 옮겨 맞춘다 — 원래 틀린 것은 조사 확인 필터가 잡는다.
 */
public final class Josa {
    private Josa() {}

    private static final Map<String, String> PAIR = Map.of("을", "를", "를", "을", "이", "가", "가", "이", "은", "는", "는", "은", "과", "와", "와", "과");
    private static final Set<String> NEED_BATCHIM = Set.of("을", "이", "은", "과");
    /** 으로를 로보다 먼저 본다. */
    private static final List<String> PARTICLES = List.of("으로", "을", "를", "이", "가", "은", "는", "과", "와", "로");

    public static boolean isHangul(char c) { return c >= 0xAC00 && c <= 0xD7A3; }

    private static int jong(char c) { return (c - 0xAC00) % 28; }

    /** prev(한글 음절) 받침 뒤에 맞는 조사 꼴. ㄹ받침 뒤 '로'는 맞다. */
    public static String fit(char prev, String particle) {
        int j = jong(prev);
        if (particle.equals("로") || particle.equals("으로")) return (j == 0 || j == 8) ? "로" : "으로";
        return NEED_BATCHIM.contains(particle) == (j != 0) ? particle : PAIR.get(particle);
    }

    /** text의 i 자리에서 시작하는 조사. 조사 다음 글자가 한글이면(설원이나·가방) 조사로 보지 않는다. */
    static String particleAt(String text, int i) {
        for (String p : PARTICLES) {
            if (!text.startsWith(p, i)) continue;
            int after = i + p.length();
            return after < text.length() && isHangul(text.charAt(after)) ? null : p;
        }
        return null;
    }

    /** text 안의 oldKo를 모두 newKo로 바꾸고, 바꾼 자리 바로 뒤 조사를 newKo 받침에 맞춘다. 조사가 없으면 String.replace와 같다. */
    public static String replaceAdjusting(String text, String oldKo, String newKo) {
        if (oldKo.isEmpty()) return text;
        boolean adjust = !newKo.isEmpty() && isHangul(oldKo.charAt(oldKo.length() - 1)) && isHangul(newKo.charAt(newKo.length() - 1));
        char oldLast = oldKo.charAt(oldKo.length() - 1);
        char newLast = adjust ? newKo.charAt(newKo.length() - 1) : 0;
        StringBuilder sb = new StringBuilder();
        int from = 0;
        int i;
        while ((i = text.indexOf(oldKo, from)) >= 0) {
            sb.append(text, from, i).append(newKo);
            from = i + oldKo.length();
            if (!adjust) continue;
            String p = particleAt(text, from);
            if (p != null && fit(oldLast, p).equals(p)) {
                sb.append(fit(newLast, p));
                from += p.length();
            }
        }
        return sb.append(text, from, text.length()).toString();
    }
}
