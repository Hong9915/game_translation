package com.gametrans.command;

import com.gametrans.llm.LlmClient.CommandFilter;
import com.gametrans.llm.LlmClient.CommandStep;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 명령으로 고치기(2026-10-08)의 규칙: 행 조건(filter) 하나와 차례로 돌리는 찾아 바꾸기 단계들. GPT가 만든 규칙이라 믿지 않는다:
 * 태그(&lt;...&gt;)·자리표시자({...})를 바꾸거나 빠뜨리거나 차례를 바꾸는 찾음은 건너뛰고(2026-10-08: 그대로 옮기는 찾음은
 * 태그에 걸쳐도 된다), 한 문장에 오래 걸리는 정규식은 끊는다.
 */
public final class CommandRule {
    static final int MAX_PATTERN = 300;
    static final int MAX_STEPS = 10;
    /**
     * 한 문장에 쓸 수 있는 시간. 정상 규칙은 마이크로초 단위지만, 5만 행을 도는 동안 막 뜬 JVM·GC 멈춤에 50ms는 걸렸다.
     * 폭주하는 정규식은 수십 초를 넘기므로 1초면 가른다.
     */
    static final long BUDGET_NANOS = 1_000_000_000L;
    private static final Pattern PROTECTED = Pattern.compile("<[^<>]*>|\\{[^{}]*}");

    private record Step(Pattern pattern, String replacement) {}

    private final boolean filterSrc;
    private final String contains;
    private final List<Step> steps;

    private CommandRule(boolean filterSrc, String contains, List<Step> steps) {
        this.filterSrc = filterSrc;
        this.contains = contains;
        this.steps = steps;
    }

    public static CommandRule compile(CommandFilter filter, List<CommandStep> steps) {
        if (steps == null || steps.isEmpty()) throw new IllegalArgumentException("바꿀 단계가 없습니다.");
        if (steps.size() > MAX_STEPS) throw new IllegalArgumentException("단계가 너무 많습니다.");
        List<Step> out = new ArrayList<>();
        for (CommandStep s : steps) {
            if (s == null) throw new IllegalArgumentException("바꿀 단계가 비어 있습니다.");
            out.add(s.regex() ? regexStep(s.find(), s.replace()) : literalStep(s.find(), s.replace()));
        }
        String contains = filter == null || filter.contains() == null ? "" : filter.contains();
        return new CommandRule(filter != null && "src".equalsIgnoreCase(filter.in()), contains, out);
    }

    private static Step literalStep(String find, String replace) {
        if (find == null || find.isEmpty()) throw new IllegalArgumentException("찾을 글자가 비어 있습니다.");
        return new Step(Pattern.compile(Pattern.quote(find)), Matcher.quoteReplacement(replace == null ? "" : replace));
    }

    private static Step regexStep(String pattern, String replacement) {
        if (pattern == null || pattern.isEmpty()) throw new IllegalArgumentException("찾을 정규식이 비어 있습니다.");
        if (pattern.length() > MAX_PATTERN) throw new IllegalArgumentException("정규식이 너무 깁니다.");
        // 모델이 \S를 \\S로 한 번 더 이스케이프하기도 한다 — 그러면 "역슬래시+S"를 찾아 아무 행도 안 걸린다.
        // 번역문에 그런 글자는 없으니 문자 부류(\S \d \w \p …)만 푼다. \\n(글자 그대로의 줄바꿈 표시)은 둔다.
        pattern = pattern.replaceAll("(?<!\\\\)\\\\\\\\([SsDdWwPp])", "\\\\$1");
        Pattern p;
        try { p = Pattern.compile(pattern); }
        catch (PatternSyntaxException e) { throw new IllegalArgumentException("정규식이 올바르지 않습니다: " + e.getDescription()); }
        // 모델이 \1 꼴로 그룹을 가리키기도 한다. Java는 $1.
        String r = (replacement == null ? "" : replacement).replaceAll("\\\\(\\d)", "\\$$1");
        try { p.matcher("").replaceAll(r); checkGroups(p, r); }
        catch (IndexOutOfBoundsException | IllegalArgumentException e) { throw new IllegalArgumentException("바꿀 꼴이 올바르지 않습니다: " + replacement); }
        return new Step(p, r);
    }

    /** 바꿀 꼴이 정규식에 없는 그룹을 가리키면 첫 찾음 때에야 터지므로 미리 본다. */
    private static void checkGroups(Pattern p, String replacement) {
        int groups = p.matcher("").groupCount();
        Matcher m = Pattern.compile("(?<!\\\\)\\$(\\d)").matcher(replacement);
        while (m.find()) if (Integer.parseInt(m.group(1)) > groups) throw new IllegalArgumentException();
    }

    /** 이 행이 규칙의 조건에 드는가. 조건이 없으면 모든 행. */
    public boolean matches(String src, String ko) {
        if (contains.isEmpty()) return true;
        String target = filterSrc ? src : ko;
        return target != null && target.contains(contains);
    }

    /** text에 단계들을 차례로 돌린 꼴. 바뀐 곳이 없으면 text 그대로. */
    public String apply(String text) {
        if (text == null || text.isEmpty()) return text;
        long until = System.nanoTime() + BUDGET_NANOS;
        String out = text;
        for (Step s : steps) out = applyStep(s, out, until);
        return out;
    }

    /**
     * 찾음 하나는 (가) 시작·끝이 태그·자리표시자 안쪽에 있지 않고 (나) 바꾼 글에 든 태그·자리표시자가 찾은 글에 온전히 든 것과
     * 같은 글자·같은 차례일 때만 바꾼다(둘 다 없어도 된다). 그래서 태그를 ( )로 잡아 $n으로 그대로 옮기는 규칙은 태그에 걸쳐도 되고,
     * 태그를 지우거나 고치거나 차례를 바꾸는 찾음은 원문째 둔다.
     */
    private static String applyStep(Step s, String text, long until) {
        List<int[]> guarded = new ArrayList<>();
        Matcher g = PROTECTED.matcher(text);
        while (g.find()) guarded.add(new int[]{g.start(), g.end()});
        Matcher m = s.pattern().matcher(new Deadline(text, until));
        StringBuilder out = new StringBuilder();
        int appendPos = 0;
        while (m.find()) {
            int start = m.start(), end = m.end();
            int before = out.length() + (start - appendPos);
            m.appendReplacement(out, s.replacement());
            appendPos = end;
            if (cutsInto(guarded, start) || cutsInto(guarded, end)
                    || !tokensIn(guarded, text, start, end).equals(tokens(out.substring(before)))) {
                // 앞 글은 그대로 두고 바꾼 부분만 찾은 글로 되돌린다.
                out.setLength(before);
                out.append(text, start, end);
            }
        }
        m.appendTail(out);
        return out.toString();
    }

    /** pos가 어느 태그·자리표시자의 안쪽(첫 글자 뒤 ~ 끝 글자 앞)에 있는가. 경계에 딱 붙은 자리는 안쪽이 아니다. */
    private static boolean cutsInto(List<int[]> guarded, int pos) {
        for (int[] r : guarded) if (pos > r[0] && pos < r[1]) return true;
        return false;
    }

    /** [start, end)에 온전히 든 태그·자리표시자들, 차례대로. */
    private static List<String> tokensIn(List<int[]> guarded, String text, int start, int end) {
        List<String> out = new ArrayList<>();
        for (int[] r : guarded) if (r[0] >= start && r[1] <= end) out.add(text.substring(r[0], r[1]));
        return out;
    }

    private static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        Matcher t = PROTECTED.matcher(s);
        while (t.find()) out.add(t.group());
        return out;
    }

    /** 정해진 시각이 지나면 읽기를 멈추는 문자열 — 정규식 엔진이 되풀이해 읽는 자리에서 끊긴다. */
    private record Deadline(CharSequence s, long until) implements CharSequence {
        @Override public char charAt(int i) {
            if (System.nanoTime() > until) throw new IllegalArgumentException("정규식이 너무 오래 걸립니다. 규칙을 더 단순하게 바꿔 주세요.");
            return s.charAt(i);
        }
        @Override public int length() { return s.length(); }
        @Override public CharSequence subSequence(int a, int b) { return new Deadline(s.subSequence(a, b), until); }
        @Override public String toString() { return s.toString(); }
    }
}
