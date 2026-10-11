package com.gametrans.xlsx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.util.CellReference;

/**
 * 헤더 이름으로 원문·영어·키 열을 추측한다. 한 시트에 이름·설명처럼 원문 열이 여러 개면
 * 접미사(_CN/_EN/_KR, 中文/英文/韩语 …)로 묶어 열 쌍 목록도 함께 돌려준다.
 */
public final class ColumnGuesser {
    /** src/en/key는 첫 쌍 기준(예전 화면 호환). pairs가 비어 있으면 접미사 규칙으로 쌍을 못 찾은 것. */
    public record Guess(String src, String en, String key, List<PairGuess> pairs) {}
    public record PairGuess(String src, String en, String ko) {}

    private ColumnGuesser() {}

    private static final Pattern SUFFIX = Pattern.compile("^(.*?)[_\\s-]*(cn|zh|zhcn|chs|sc|en|eng|kr|ko|kor)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFIX = Pattern.compile("^(cn|zh|zhcn|chs|sc|en|eng|kr|ko|kor)[_\\s-]+(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CJK_SUFFIX = Pattern.compile("^(.*?)(简体中文|简中|中文|英文|英语|韩文|韩语|韩国语)$");

    public static Guess guess(List<String> headers) {
        String src = null, en = null, key = null;
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i) == null ? "" : headers.get(i);
            String letter = CellReference.convertNumToColString(i);
            String lower = h.toLowerCase(Locale.ROOT);
            String[] tokens = lower.split("[^a-z0-9\\u4e00-\\u9fff]+");
            if (src == null && (h.contains("中文") || h.contains("简体") || hasToken(tokens, "cn", "zh", "chinese", "zhcn", "cns"))) src = letter;
            else if (en == null && (h.contains("英文") || h.contains("英语") || hasToken(tokens, "en", "english", "eng"))) en = letter;
            else if (key == null && (hasToken(tokens, "id", "key") || lower.endsWith("id") || lower.endsWith("_key") || h.contains("编号") || h.contains("序号"))) key = letter;
        }
        List<PairGuess> pairs = pairs(headers);
        if (!pairs.isEmpty()) {
            // 첫 쌍에 영어 열이 없으면 토큰 규칙으로 찾은 영어 열("English" 등)을 보탠다.
            PairGuess first = pairs.get(0);
            if (first.en() == null && en != null) pairs.set(0, first = new PairGuess(first.src(), en, first.ko()));
            src = first.src();
            en = first.en();
        }
        return new Guess(src, en, key, pairs);
    }

    /** 헤더를 (기본 이름, 언어)로 쪼개 기본 이름별로 묶는다. 원문(CN) 열이 있는 묶음만 쌍이 된다. */
    static List<PairGuess> pairs(List<String> headers) {
        Map<String, String[]> groups = new LinkedHashMap<>();  // base → [cn, en, ko]
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i) == null ? "" : headers.get(i).trim();
            if (h.isEmpty()) continue;
            String[] split = splitLang(h);
            if (split == null) continue;
            String base = split[0], lang = split[1];
            if (isTraditional(base)) continue;
            String[] g = groups.computeIfAbsent(base, k -> new String[3]);
            int slot = switch (lang) { case "cn" -> 0; case "en" -> 1; default -> 2; };
            if (g[slot] == null) g[slot] = CellReference.convertNumToColString(i);
        }
        List<PairGuess> out = new ArrayList<>();
        for (String[] g : groups.values()) if (g[0] != null) out.add(new PairGuess(g[0], g[1], g[2]));
        return out;
    }

    /** "技能名称_CN" → [技能名称, cn], "CN_Text" → [Text, cn], "韩语" → ["", ko]. 못 나누면 null. */
    private static String[] splitLang(String h) {
        Matcher m = CJK_SUFFIX.matcher(h);
        if (m.matches()) return new String[] {m.group(1).trim(), cjkLang(m.group(2))};
        m = SUFFIX.matcher(h);
        if (m.matches()) return new String[] {m.group(1).trim(), lang(m.group(2))};
        m = PREFIX.matcher(h);
        if (m.matches()) return new String[] {m.group(2).trim(), lang(m.group(1))};
        return null;
    }

    private static String lang(String code) {
        return switch (code.toLowerCase(Locale.ROOT)) {
            case "cn", "zh", "zhcn", "chs", "sc" -> "cn";
            case "en", "eng" -> "en";
            default -> "ko";
        };
    }

    private static String cjkLang(String word) {
        if (word.contains("中")) return "cn";
        if (word.contains("英")) return "en";
        return "ko";
    }

    private static boolean isTraditional(String base) {
        String b = base.toLowerCase(Locale.ROOT);
        return b.contains("繁") || b.contains("tw") || b.contains("hk") || b.contains("trad");
    }

    private static boolean hasToken(String[] tokens, String... wanted) {
        for (String t : tokens) for (String w : wanted) if (t.equals(w)) return true;
        return false;
    }
}
