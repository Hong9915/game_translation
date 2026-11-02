package com.gametrans.deepl;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DeepL에 보낼 문장의 태그 감싸기(설계 2026-10-12 §3). 실험(태그 행 200개)에서 고른 방식:
 * 숫자만 든 색 구간은 태그를 떼고 숫자만 보낸 뒤 번역문에서 그 숫자를 찾아 다시 감싸고(SMART), 한자가 든 색 구간은 짝 요소 &lt;c&gt;,
 * 나머지 태그·{0}·%s·글자 그대로의 \n·실제 줄바꿈·&lt;br/&gt;는 &lt;x/&gt;로 보낸다. SMART가 실패한 문장은 모든 태그를 &lt;x/&gt;로(WRAP_ALL) 다시 보낸다.
 */
public final class DeepLTags {
    private DeepLTags() {}

    public enum Mode { SMART, WRAP_ALL }

    /** xml: DeepL에 보낼 문장. tokens: &lt;x i&gt;·&lt;c i&gt;의 i가 가리키는 원래 태그. spans: SMART에서 태그를 뗀 숫자 색 구간(열기, 안 글자, 닫기). */
    public record Prepared(String xml, List<String> tokens, List<String[]> spans) {}

    private static final String HAN = "\\u4e00-\\u9fff";
    static final Pattern NUM_SPAN = Pattern.compile("(<color=[^>]*>)([^<" + HAN + "]+)(</color>)");
    static final Pattern TOKEN = Pattern.compile("<color=[^>]*>|</color>|</?[A-Za-z][^<>]*>|\\{\\d+\\}|%[sd]|\\\\n|\\n");
    static final Pattern TOKEN_ALL = Pattern.compile("<color=[^>]*>[^<" + HAN + "]+</color>|</?[A-Za-z][^<>]*>|\\{\\d+\\}|%[sd]|\\\\n|\\n");
    static final Pattern CHECK = Pattern.compile("</?[A-Za-z][^<>]*>|\\{\\d+\\}|%[sd]|\\\\n|\\n");
    static final Pattern COLOR = Pattern.compile("<color=[^>]*>|</color>");
    static final Pattern C_OPEN = Pattern.compile("<c i=\"(\\d+)\">");
    static final Pattern X_TAG = Pattern.compile("<x i=\"(\\d+)\"\\s*/>|<x i=\"(\\d+)\"></x>");
    static final Pattern HAN_CHAR = Pattern.compile("[" + HAN + "]");
    static final Pattern ANY_TAG = Pattern.compile("<[^>]*>");
    private static final String BAD = "\u0000";

    public static Prepared prepare(String src, Mode mode) {
        String text = src == null ? "" : src;
        List<String> tokens = new ArrayList<>();
        List<String[]> spans = new ArrayList<>();
        Pattern tokenPattern = TOKEN_ALL;
        boolean pairs = false;
        if (mode == Mode.SMART) {
            Matcher m = NUM_SPAN.matcher(text);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                spans.add(new String[] {m.group(1), m.group(2), m.group(3)});
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(2)));
            }
            m.appendTail(sb);
            text = sb.toString();
            tokenPattern = TOKEN;
            pairs = balancedColors(text);
        }
        StringBuilder out = new StringBuilder();
        Matcher m = tokenPattern.matcher(text);
        int pos = 0;
        while (m.find()) {
            out.append(escape(text.substring(pos, m.start())));
            pos = m.end();
            String t = m.group();
            if (pairs && t.equals("</color>")) { out.append("</c>"); continue; }
            tokens.add(t);
            int i = tokens.size() - 1;
            if (pairs && t.startsWith("<color=")) out.append("<c i=\"").append(i).append("\">");
            else out.append("<x i=\"").append(i).append("\"/>");
        }
        out.append(escape(text.substring(pos)));
        return new Prepared(out.toString(), tokens, spans);
    }

    /** 번역문을 원래 태그로 되돌린다. 모르는 표시가 남거나 숫자 색 구간을 다시 감싸지 못하면 null. */
    public static String restore(String translated, Prepared p) {
        if (translated == null) return null;
        String t = C_OPEN.matcher(translated).replaceAll(r -> Matcher.quoteReplacement(token(p, r.group(1))));
        t = t.replace("</c>", "</color>");
        t = X_TAG.matcher(t).replaceAll(r -> Matcher.quoteReplacement(token(p, r.group(1) != null ? r.group(1) : r.group(2))));
        if (t.contains(BAD) || t.contains("<x ") || t.contains("<c ") || t.contains("</x>")) return null;
        t = unescape(t);
        int from = 0;
        for (String[] s : p.spans()) {
            int at = t.indexOf(s[1], from);
            if (at < 0) return null;
            t = t.substring(0, at) + s[0] + s[1] + s[2] + t.substring(at + s[1].length());
            from = at + s[0].length() + s[1].length() + s[2].length();
        }
        return t;
    }

    /** 태그·변수·글자 그대로의 \n·실제 줄바꿈이 원문과 순서까지 같은지. */
    public static boolean tagsMatch(String src, String ko) {
        return ko != null && ordered(src).equals(ordered(ko));
    }

    /** 태그가 맞고, 태그를 뺀 글자에 한자가 남지 않았는지. */
    public static boolean clean(String src, String ko) {
        return tagsMatch(src, ko) && !HAN_CHAR.matcher(ANY_TAG.matcher(ko).replaceAll("")).find();
    }

    static List<String> ordered(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        Matcher m = CHECK.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** 색 태그가 겹침 없이 열고 닫는 짝으로만 들어 있는지. 아니면 짝 요소로 보내면 XML이 깨진다. */
    static boolean balancedColors(String s) {
        int depth = 0;
        Matcher m = COLOR.matcher(s);
        while (m.find()) {
            if (m.group().startsWith("</")) { if (--depth < 0) return false; }
            else if (++depth > 1) return false;
        }
        return depth == 0;
    }

    private static String token(Prepared p, String index) {
        int i = Integer.parseInt(index);
        return i < p.tokens().size() ? p.tokens().get(i) : BAD;
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }
}
