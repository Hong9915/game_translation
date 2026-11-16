package com.gametrans.rules;

import java.util.regex.Pattern;

/**
 * 줄 끝 마침표 빼기(설계 2026-11-16): 줄바꿈(&lt;br&gt;·진짜 줄바꿈·글자 \n)이나 글 끝 바로 앞의 "." 하나를 지운다.
 * 사이의 닫는 태그·공백은 건너뛴다. 문장 중간 "."와 말줄임표(앞 글자가 "."·"…"·"。")는 그대로 둔다.
 */
public final class PeriodStripper {
    private PeriodStripper() {}

    private static final Pattern LINE_END_DOT = Pattern.compile(
            "(?<![.…。])\\.(?=(?:</[^>]+>|[ \\t])*(?:(?i:<br\\s*/?>)|\\r?\\n|\\\\n|$))");

    public static String strip(String ko) {
        return ko == null ? null : LINE_END_DOT.matcher(ko).replaceAll("");
    }

    public static boolean candidate(String ko) {
        return ko != null && LINE_END_DOT.matcher(ko).find();
    }
}
