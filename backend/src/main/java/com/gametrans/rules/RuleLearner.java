package com.gametrans.rules;

import com.gametrans.rules.RuleText.Clause;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 손 수정에서 문체 규칙(서술식→개조식)을 배운다(설계 2026-11-13 §4). 순수 계산 — DB·GPT 없음.
 * 낱말 규칙은 2026-11-13 사용자 결정으로 뺐다.
 */
public class RuleLearner {
    public record Edit(long segmentId, String src, String before, String after, Instant at) {}
    /** 게임 행. skipped·confirmed 행은 이미 빠진 것. ko = latest_ko. */
    public record Row(long segmentId, long projectId, String src, String ko, Character textKind) {}
    public record RuleKey(String type, String of, String nf, Character textKind) {}
    /** anchors: 원문 짝 후보(첫 번째가 대표 = 게임에서 가장 흔한 것). verdict: STYLE만. */
    public record Learned(RuleKey key, List<String> anchors, Set<String> shapes, Set<String> styles, int fixed, int left,
                          List<Long> candidates, double ratio, double sameAsHuman, Instant lastEdit, boolean flipped,
                          String verdict, int converted, int kept) {}

    public static final String STYLE_OF = "서술식", STYLE_NF = "개조식";
    /** 줄 끝 마침표 빼기 규칙(2026-11-16) — 글 종류 상관없이 게임 전체에 하나. */
    public static final RuleKey PERIOD = new RuleKey("PERIOD", "줄 끝 마침표", "없음", null);
    static final int MIN_DECISIONS = 10;
    /** 문체 규칙을 만들지 않는 글 종류: 대사(D)·공지·메일(N). 서술식을 그대로 두는 글이다(2026-11-13 사용자 결정). */
    public static final String EXCLUDED_KINDS = "DN";
    static final double RULE_AT = 0.7, KEEP_AT = 0.3;

    public List<Learned> learn(List<Edit> edits, List<Row> rows, Map<Character, String> policies,
                               Map<RuleKey, Set<Long>> exclusions, boolean dropHa) {
        List<Learned> out = styles(edits, rows, policies, exclusions, dropHa);
        Set<Long> excl = exclusions.getOrDefault(PERIOD, Set.of());
        List<Long> cand = new ArrayList<>();
        for (Row r : rows) if (!excl.contains(r.segmentId()) && PeriodStripper.candidate(r.ko())) cand.add(r.segmentId());
        if (!cand.isEmpty())
            out.add(new Learned(PERIOD, List.of(), Set.of(), Set.of(), 0, cand.size(), cand, 0, 0, null, false, "RULE", 0, 0));
        return out;
    }

    /** `X합니다→X`가 `→X함`보다 많거나 같으면 true(설계 §4). */
    public static boolean dropHaPreferred(List<Edit> edits) {
        int drop = 0, keep = 0;
        Pattern p = Pattern.compile("([가-힣]{2,})합니다");
        for (Edit e : edits) {
            Matcher m = p.matcher(e.before());
            while (m.find()) {
                String stem = m.group(1);
                if (e.after().contains(stem + "함")) keep++;
                else if (e.after().contains(stem)) drop++;
            }
        }
        return drop >= keep;
    }

    /** 문체 규칙 후보: 그 글 종류이고 바꿀 서술식 마디(의문 제외)가 있다. */
    public static boolean styleCandidate(Row row, Character kind) {
        return kind.equals(row.textKind()) && RuleText.hasConvertible(row.ko());
    }

    // ---- 문체 규칙 ----

    private List<Learned> styles(List<Edit> edits, List<Row> rows, Map<Character, String> policies,
                                 Map<RuleKey, Set<Long>> exclusions, boolean dropHa) {
        Map<Long, Character> kindOf = new HashMap<>();
        for (Row r : rows) if (r.textKind() != null) kindOf.put(r.segmentId(), r.textKind());
        Map<Character, List<Edit>> conv = new TreeMap<>(), kept = new TreeMap<>();
        for (Edit e : edits) {
            Character k = kindOf.get(e.segmentId());
            if (k == null || EXCLUDED_KINDS.indexOf(k) >= 0) continue;
            List<Clause> a = RuleText.clauses(e.before()), b = RuleText.clauses(e.after());
            long fa = RuleText.count(a, Clause.FORMAL), fb = RuleText.count(b, Clause.FORMAL);
            long na = RuleText.count(a, Clause.NOUN), nb = RuleText.count(b, Clause.NOUN);
            if (fa > fb && nb > na) conv.computeIfAbsent(k, x -> new ArrayList<>()).add(e);
            else if (fa > 0 && fb >= fa) kept.computeIfAbsent(k, x -> new ArrayList<>()).add(e);
        }
        Set<Character> kinds = new TreeSet<>(conv.keySet());
        kinds.addAll(kept.keySet());
        // 손 수정이 없어도 서술식이 남은 행이 있는 종류는 찾아서 올린다(2026-11-16 사용자 결정: 배우지 말고 찾기)
        for (Row r : rows) {
            Character k = r.textKind();
            if (k != null && EXCLUDED_KINDS.indexOf(k) < 0 && !kinds.contains(k) && styleCandidate(r, k)) kinds.add(k);
        }
        List<Learned> out = new ArrayList<>();
        for (Character k : kinds) {
            List<Edit> cv = conv.getOrDefault(k, List.of()), kp = kept.getOrDefault(k, List.of());
            int total = cv.size() + kp.size();
            double ratio = total == 0 ? 0 : (double) cv.size() / total;
            String policy = policies.get(k);
            String verdict = total < MIN_DECISIONS && policy == null ? "INSUFFICIENT"
                    : "CONVERT".equals(policy) ? "RULE" : "KEEP".equals(policy) ? "KEEP"
                    : ratio >= RULE_AT ? "RULE" : ratio <= KEEP_AT ? "KEEP" : "DECIDE";
            RuleKey key = new RuleKey("STYLE", STYLE_OF, STYLE_NF, k);
            Set<Long> excl = exclusions.getOrDefault(key, Set.of());
            List<Long> cand = new ArrayList<>();
            for (Row r : rows)
                if (!excl.contains(r.segmentId()) && styleCandidate(r, k))
                    cand.add(r.segmentId());
            long same = cv.stream().filter(e -> StyleConverter.convert(e.before(), dropHa).text().equals(e.after())).count();
            Instant last = new ArrayList<Edit>() {{ addAll(cv); addAll(kp); }}.stream().map(Edit::at).max(Comparator.naturalOrder()).orElse(null);
            out.add(new Learned(key, List.of(), Set.of(), Set.of(), cv.size(), cand.size(), cand, ratio,
                    cv.isEmpty() ? 0 : (double) same / cv.size(), last, false, verdict, cv.size(), kp.size()));
        }
        return out;
    }
}
