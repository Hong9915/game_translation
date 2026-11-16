package com.gametrans.rules;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 서술형 마디 끝(~습니다·~하세요·~한다 등)을 개조식으로 바꾸는 표(설계 2026-11-13 §4). 마침표는 그대로 둔다. */
public final class StyleConverter {
    private StyleConverter() {}

    /** complete = 의문 마디 말고는 서술식 마디가 남지 않았다. */
    public record Converted(String text, boolean complete) {}

    /** 마디 끝: 한글 낱말 뒤에 (닫는 태그들) + 문장부호·줄바꿈·글 끝. 낱말이 태그를 넘지는 않는다. */
    private static final String END = "(?=(?:<[^>]+>)*\\s*(?:[.!?。…~]+|$|\\n|\\\\n))";
    private static final Pattern CAN = Pattern.compile("([가-힣]+?)(?:하실|할) 수 있습니다" + END);
    private static final Pattern WORD = Pattern.compile("([가-힣]*(?:니다|하세요|하십시오|한다|된다|있다|없다))" + END);
    private static final Pattern LEFT = Pattern.compile("([가-힣]*(?:니다|십시오|하세요|한다|된다|있다|없다))" + END);

    public static Converted convert(String ko, boolean dropHa) {
        if (ko == null) return new Converted(null, true);
        String s = CAN.matcher(ko).replaceAll("$1 가능");
        Matcher m = WORD.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(word(m.group(1), dropHa)));
        m.appendTail(out);
        String text = out.toString();
        return new Converted(text, !LEFT.matcher(text).find());
    }

    private static String word(String w, boolean dropHa) {
        // ② 실패했습니다 → 실패: 사람은 끝을 떼고 명사만 남겼다(1,583번). 못했습니다·안 했습니다는 못함.
        if (w.endsWith("했습니다")) {
            String stem = w.substring(0, w.length() - 4);
            return dropHa && stem.length() >= 2 ? stem : stem + "함";
        }
        // ③ 선택하세요·사용하십시오 → 선택·사용
        if (w.endsWith("하십시오") || w.endsWith("하세요")) {
            String stem = w.substring(0, w.length() - (w.endsWith("하십시오") ? 4 : 3));
            return stem.length() >= 2 ? stem : w;
        }
        // ④ 지속된다·흡수한다 → 지속·흡수, 있다·없다 → 있음·없음
        if (w.endsWith("한다") || w.endsWith("된다")) {
            String stem = w.substring(0, w.length() - 2);
            return dropHa && stem.length() >= 2 ? stem : stem + (w.endsWith("한다") ? "함" : "됨");
        }
        if (w.endsWith("있다") || w.endsWith("없다")) return w.substring(0, w.length() - 1) + "음";
        if (w.endsWith("되었습니다")) return w.substring(0, w.length() - 5) + "됨";
        if (w.endsWith("됐습니다")) return w.substring(0, w.length() - 4) + "됨";
        if (w.endsWith("합니다")) {
            String stem = w.substring(0, w.length() - 3);
            return dropHa && stem.length() >= 2 ? stem : stem + "함";
        }
        if (w.endsWith("입니다")) return w.substring(0, w.length() - 3) + "임";
        if (w.endsWith("습니다")) return w.substring(0, w.length() - 3) + "음";
        if (w.length() >= 3) {
            char c = w.charAt(w.length() - 3);
            if (c >= 0xAC00 && c <= 0xD7A3 && (c - 0xAC00) % 28 == 17)
                return w.substring(0, w.length() - 3) + (char) (c - 1);   // 받침 ㅂ(17) → ㅁ(16)
        }
        return w;
    }
}
