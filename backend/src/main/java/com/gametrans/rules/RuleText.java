package com.gametrans.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 문체 규칙이 글을 다루는 순수 함수들(설계 2026-11-13 §4). 스프링 없이 쓴다. */
public final class RuleText {
    private RuleText() {}

    public enum Clause { FORMAL, NOUN, OTHER }

    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern CLAUSE_END = Pattern.compile("([가-힣]+)(?=\\s*(?:[.!?。…~]+|$|\\n|\\\\n))");
    /**
     * 개조식으로 줄일 서술형 끝(2026-11-13 손 수정 분석): ~습니다/~ㅂ니다/~십시오/~니까, ~하세요(명사+하세요),
     * ~한다/~된다/있다/없다. ~니까는 의문이라 바꾸지 않는다(hasConvertible).
     */
    private static final Pattern FORMAL_END = Pattern.compile("(습니다|ㅂ니다|니다|십시오|니까|한다|된다|있다|없다)$|^[가-힣]{2,}하세요$");
    private static final Pattern NOUN_END = Pattern.compile("(음|함|됨|임|증가|감소|획득|부여|지속|회복|가능|발동|제거|면역|적용|피해)$");
    /** 용언 끝(~다·~요·~어 …)이 아니면 명사로 끝난 마디 — "실패했습니다 → 실패"처럼 끝을 뗀 개조식. */
    private static final Pattern VERB_END = Pattern.compile("(다|요|까|오|어|아|지|야|해|네|게|래|자|냐|니|군|걸|며|고|서|면)$");

    public static String stripTags(String s) { return s == null ? "" : TAG.matcher(s).replaceAll(""); }

    public static List<Clause> clauses(String ko) {
        List<Clause> out = new ArrayList<>();
        Matcher m = CLAUSE_END.matcher(stripTags(ko));
        while (m.find()) {
            String w = m.group(1);
            out.add(FORMAL_END.matcher(w).find() ? Clause.FORMAL
                    : NOUN_END.matcher(w).find() || !VERB_END.matcher(w).find() ? Clause.NOUN : Clause.OTHER);
        }
        return out;
    }

    /** 개조식으로 바꿀 서술식 마디가 있다 — 의문(~니까) 마디만 있으면 바꿀 게 없다. */
    public static boolean hasConvertible(String ko) {
        Matcher m = CLAUSE_END.matcher(stripTags(ko));
        while (m.find()) {
            String w = m.group(1);
            if (FORMAL_END.matcher(w).find() && !w.endsWith("니까")) return true;
        }
        return false;
    }

    public static long count(List<Clause> cs, Clause c) { return cs.stream().filter(x -> x == c).count(); }
}
