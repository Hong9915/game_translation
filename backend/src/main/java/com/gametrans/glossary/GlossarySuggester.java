package com.gametrans.glossary;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SRC+PARTIAL 단어장 항목의 "기존 표기" 후보를 게임의 (원문, 한국어) 목록만으로 뽑는다(설계 §2.2).
 * 같은 게임 안에서 한 중국어 용어는 대개 한 가지 한국어로 옮겨져 있으므로, 원문에 find가 든 행들의 한국어에 공통으로
 * 나오는 낱말이 그 용어의 지금 번역이다. 순수 함수 — DB·모델 없이 돈다.
 *
 * 1. R = 원문에 find가 든 행.
 * 2. R의 한국어를 공백으로 자른 토큰마다 한글 구간의 접두사(2글자 이상)와 인접 두 토큰을 후보로 모은다.
 *    접두사만 보는 이유: 조사는 뒤에 붙으므로 어간은 늘 접두사이고, "보물상자"의 "물상" 같은 낱말 속 조각은 후보가 아니다.
 * 3. Dice = 2a/(|R|+b) (a: R 안에서 든 행 수, b: 게임 전체에서 든 행 수). a < 2, a < 0.2|R|는 버린다.
 * 4. 설명 제거: 상위 5개 후보의 토큰이 find보다 다른 원문 조각(2글자)과 더 잘 맞으면 그 조각의 번역으로 보고 뺀다
 *    (계획은 升星보다 计划와 맞으므로 "계획"·"승급 계획"이 빠진다).
 * 5. 포함 관계(짧은 후보가 긴 후보의 접두사이거나 둘째 낱말): 긴 쪽에서 짧은 쪽을 뗀 나머지가 조사·어미면 긴 쪽을
 *    뺀다("승급을"보다 "승급"). 아니면 긴 쪽 Dice가 같거나 높으면 짧은 쪽을 빼고, 접두사 관계에서 긴 쪽이 낮으면 긴 쪽을
 *    뺀다. 둘째 낱말 관계에서 긴 쪽이 낮으면 둘 다 남긴다("봉투"·"행운 봉투").
 * 6. 상위 3개. 예시는 그 후보가 든 R의 첫 행과 replacePartial 결과.
 *
 * "어떤 문자열이 든 행"은 모두 {@link Corpus}가 글자 바이그램 색인으로 찾는다. 전체 행을 훑던 때와 결과는 같고,
 * 한 게임의 용어 수천 개를 한 번에 볼 때(단어장 추천)는 Corpus 하나를 만들어 돌려 쓴다.
 */
public final class GlossarySuggester {
    private GlossarySuggester() {}

    public record Row(String src, String ko) {}
    public record Example(String src, String before, String after) {}
    public record Candidate(String oldKo, int rows, Example example) {}
    public record Result(int rows, List<Candidate> candidates) {}

    static final int MIN_ROWS = 2;
    static final double MIN_SHARE = 0.2;
    static final int EXPLAIN_TOP = 5;
    static final int SOURCE_PIECES = 20;
    static final int MAX_CANDIDATES = 3;

    private static final Pattern HANGUL_RUN = Pattern.compile("[가-힣]+");
    /** 어간 뒤에 붙는 조사·어미. 긴 후보에서 짧은 후보를 뗀 나머지가 이 가운데 하나면 짧은 쪽(어간)을 남긴다. */
    static final Set<String> SUFFIXES = Set.of(
            "이", "가", "을", "를", "은", "는", "의", "에", "에서", "로", "으로", "와", "과", "도", "만", "에게", "께", "한테", "까지", "부터",
            "이다", "입니다", "다", "라", "이라", "이라고", "라고",
            "하다", "하는", "한", "할", "해", "하여", "하고", "하면", "하기", "합니다", "했다", "했습니다", "하지", "하거나", "하려면", "하며", "하세요",
            "되다", "되는", "된", "될", "되어", "돼", "됩니다", "됐다", "되면", "되기", "됨", "됐습니다");
    private static final Pattern CJK_RUN = Pattern.compile("[一-鿿]+");

    public static Result suggest(List<Row> all, String find, String ko) { return new Corpus(all).suggest(find, ko); }

    /** 같은 행 목록으로 여러 용어를 물을 때 쓴다. 색인과 "든 행" 계산을 용어끼리 나눠 쓴다. */
    public static Corpus corpus(List<Row> all) { return new Corpus(all); }

    /**
     * 한 게임의 행 목록과 그 위의 글자 바이그램 색인. 2글자 이상 문자열이 든 행은 가장 드문 바이그램의 행들만 contains로
     * 확인해 찾는다(전체를 훑은 것과 같은 집합). 계산한 집합을 기억하므로 스레드 하나에서만 쓴다 — 요청마다 새로 만든다.
     */
    public static final class Corpus {
        private final List<Row> all;
        private final Map<Integer, int[]> koIndex, srcIndex;
        private final Map<String, BitSet> koMasks = new HashMap<>(), srcMasks = new HashMap<>();
        private final Map<String, Integer> koCounts = new HashMap<>();

        private Corpus(List<Row> all) {
            this.all = all;
            this.koIndex = index(all, true);
            this.srcIndex = index(all, false);
        }

        public Result suggest(String find, String ko) { return GlossarySuggester.suggest(this, find, ko); }

        BitSet koMask(String needle) { return koMasks.computeIfAbsent(needle, n -> mask(n, true)); }

        BitSet srcMask(String needle) { return srcMasks.computeIfAbsent(needle, n -> mask(n, false)); }

        /** 그 한국어가 든 행. koMask와 달리 기억하지 않는다 — 표기 갈림은 후보마다 한 번만 묻고, 수천 개를 기억하면 요청 하나가 수백 MB가 된다. */
        BitSet koRows(String needle) {
            BitSet known = koMasks.get(needle);
            return known != null ? known : mask(needle, true);
        }

        /** 게임 전체에서 그 한국어가 든 행 수. 후보마다 묻기 때문에 집합을 만들지 않고 세기만 한다. */
        int koCount(String needle) {
            BitSet known = koMasks.get(needle);
            if (known != null) return known.cardinality();
            return koCounts.computeIfAbsent(needle, n -> {
                int[] rows = rarest(n, koIndex);
                if (rows == null) return mask(n, true).cardinality();
                if (n.length() == 2) return rows.length;
                int c = 0;
                for (int i : rows) if (all.get(i).ko().contains(n)) c++;
                return c;
            });
        }

        private BitSet mask(String needle, boolean ko) {
            BitSet b = new BitSet(all.size());
            int[] rows = rarest(needle, ko ? koIndex : srcIndex);
            if (rows == null) {   // 한 글자 이하는 색인에 없다 — 전체를 훑는다
                for (int i = 0; i < all.size(); i++) { String t = text(i, ko); if (t != null && t.contains(needle)) b.set(i); }
            } else {
                for (int i : rows) if (needle.length() == 2 || text(i, ko).contains(needle)) b.set(i);
            }
            return b;
        }

        private String text(int i, boolean ko) { return ko ? all.get(i).ko() : all.get(i).src(); }

        /** needle의 바이그램 가운데 든 행이 가장 적은 것의 행 목록. 2글자 미만이면 null, 색인에 없는 바이그램이 있으면 빈 목록. */
        private static int[] rarest(String needle, Map<Integer, int[]> index) {
            if (needle.length() < 2) return null;
            int[] best = null;
            for (int i = 0; i + 2 <= needle.length(); i++) {
                int[] rows = index.get(key(needle.charAt(i), needle.charAt(i + 1)));
                if (rows == null) return NO_ROWS;
                if (best == null || rows.length < best.length) best = rows;
            }
            return best;
        }

        private static final int[] NO_ROWS = {};

        private static int key(char a, char b) { return (a << 16) | b; }

        private static Map<Integer, int[]> index(List<Row> all, boolean ko) {
            Map<Integer, int[]> lists = new HashMap<>();   // 값의 [0]은 쓴 칸 수, 그 뒤가 행 번호(오름차순)
            for (int r = 0; r < all.size(); r++) {
                String t = ko ? all.get(r).ko() : all.get(r).src();
                if (t == null) continue;
                for (int i = 0; i + 2 <= t.length(); i++) {
                    int k = key(t.charAt(i), t.charAt(i + 1));
                    int[] l = lists.get(k);
                    if (l == null) lists.put(k, l = new int[]{0, 0, 0, 0});
                    if (l[0] > 0 && l[l[0]] == r) continue;   // 같은 행에 두 번 나온 바이그램
                    if (l[0] + 1 == l.length) lists.put(k, l = java.util.Arrays.copyOf(l, l.length * 2));
                    l[++l[0]] = r;
                }
            }
            lists.replaceAll((k, l) -> java.util.Arrays.copyOfRange(l, 1, l[0] + 1));
            return lists;
        }
    }

    private static Result suggest(Corpus corpus, String find, String ko) {
        BitSet findMask = corpus.mask(find, false);   // 용어마다 한 번만 쓰이니 기억하지 않는다
        List<Row> rows = new ArrayList<>();
        for (int i = findMask.nextSetBit(0); i >= 0; i = findMask.nextSetBit(i + 1)) if (corpus.all.get(i).ko() != null) rows.add(corpus.all.get(i));
        int n = rows.size();
        if (n == 0) return new Result(0, List.of());

        // 2. 후보와 a
        Map<String, Integer> a = new LinkedHashMap<>();
        for (Row r : rows) for (String c : candidatesOf(r.ko())) a.merge(c, 1, Integer::sum);

        // 3. Dice
        record Scored(String c, int a, double dice) {}
        List<Scored> scored = new ArrayList<>();
        for (Map.Entry<String, Integer> e : a.entrySet()) {
            int ac = e.getValue();
            if (ac < MIN_ROWS || ac < MIN_SHARE * n) continue;
            int b = corpus.koCount(e.getKey());
            scored.add(new Scored(e.getKey(), ac, 2.0 * ac / (n + b)));
        }
        scored.sort(Comparator.comparingDouble(Scored::dice).reversed()
                .thenComparing(Comparator.comparingInt(Scored::a).reversed())
                .thenComparingInt(s -> s.c().length()));

        // 4. 설명 제거 — 점수 순으로 내려가며 설명되지 않은 후보를 EXPLAIN_TOP개 모을 때까지 본다.
        //    상위 N개만 잘라 보면 이웃 낱말 하나가 만든 후보(보물·보물상·보물상자·승급 보물…)가 상위를 채우고 전부 빠져
        //    진짜 후보가 검토되지 못한다.
        List<String> pieces = sourcePieces(rows, find);
        Map<String, BitSet> pieceMasks = new HashMap<>();
        for (String g : pieces) pieceMasks.put(g, corpus.srcMask(g));
        List<Scored> survivors = new ArrayList<>();
        Map<String, Boolean> explainedCache = new HashMap<>();
        for (Scored s : scored) {
            if (survivors.size() == EXPLAIN_TOP) break;
            boolean explained = false;
            for (String t : s.c().split(" ")) {
                explained |= explainedCache.computeIfAbsent(t, tok -> {
                    BitSet tm = corpus.koMask(tok);
                    double withFind = dice(tm, findMask);
                    for (BitSet gm : pieceMasks.values()) if (dice(tm, gm) > withFind) return true;
                    return false;
                });
            }
            if (!explained) survivors.add(s);
        }

        // 5. 포함 관계
        Set<String> dropped = new HashSet<>();
        for (Scored s : survivors) for (Scored l : survivors) {
            String sc = s.c(), lc = l.c();
            if (s == l || sc.length() >= lc.length()) continue;
            boolean prefix = lc.startsWith(sc);
            // 두 토큰 후보의 둘째 낱말("행운 봉투"의 "봉투")도 포함 관계로 본다
            boolean secondToken = lc.contains(" ") && lc.substring(lc.indexOf(' ') + 1).startsWith(sc);
            if (!prefix && !secondToken) continue;
            if (prefix && SUFFIXES.contains(lc.substring(sc.length()).strip())) { dropped.add(lc); continue; }
            if (l.dice() >= s.dice()) dropped.add(sc);
            else if (prefix) dropped.add(lc);
            // 둘째 낱말 관계("행운 봉투" ⊃ "봉투")에서 짧은 쪽 점수가 높으면 둘 다 남긴다 — 검수자가 고른다
        }
        survivors.removeIf(s -> dropped.contains(s.c()));

        // 6. 상위 3개 + 예시
        List<Candidate> out = new ArrayList<>();
        for (Scored s : survivors) {
            if (out.size() == MAX_CANDIDATES) break;
            Row ex = rows.stream().filter(r -> r.ko().contains(s.c())).findFirst().orElse(null);
            if (ex == null) continue;   // 후보는 늘 어떤 행의 부분 문자열이어야 한다 — 아니면 보여 줄 예시가 없다
            String after = ko == null || ko.isBlank() ? null : GlossaryApplier.replacePartial(ex.ko(), s.c(), ko);
            out.add(new Candidate(s.c(), s.a(), new Example(ex.src(), ex.ko(), after)));
        }
        return new Result(n, out);
    }

    /**
     * 한 한국어 문장의 후보. 중복 없음.
     * - 토큰(공백 기준)의 한글 구간마다 2글자 이상 접두사: "승급을" → 승급, 승급을. 조사는 뒤에 붙으므로 어간은 늘
     *   접두사이고, "보물상자"의 "물상"·"상자" 같은 낱말 속 조각은 후보로 삼지 않는다.
     * - 인접 두 토큰: 앞 토큰 끝의 한글 구간 + " " + 뒤 토큰 시작의 한글 구간의 2글자 이상 접두사. 뒤 토큰에 조사가 붙어도
     *   ("행운 봉투가") 어간 쌍 "행운 봉투"가 후보에 들어가게 하기 위해서다. "행운 봉"처럼 잘린 접두사는
     *   포함 관계 정리에서 더 긴 후보에 밀린다. 앞 토큰이 한글로 끝나지 않거나("승급!", "승급(1성)", "승급</color>")
     *   뒤 토큰이 한글로 시작하지 않으면 쌍을 만들지 않는다 — 문장에 실제로 없는 "승급 보상"을 만들면 b≈0으로 1순위가 된다.
     */
    static Set<String> candidatesOf(String ko) {
        Set<String> out = new HashSet<>();
        String[] tokens = ko.trim().split("\\s+");
        List<String> tail = new ArrayList<>();   // 토큰 끝에 붙은 한글 구간(없으면 "")
        List<String> head = new ArrayList<>();   // 토큰 시작에 붙은 한글 구간(없으면 "")
        for (String tok : tokens) {
            String first = "", last = "";
            Matcher m = HANGUL_RUN.matcher(tok);
            while (m.find()) {
                String run = m.group();
                for (int j = 2; j <= run.length(); j++) out.add(run.substring(0, j));
                if (m.start() == 0) first = run;
                if (m.end() == tok.length()) last = run;
            }
            head.add(first);
            tail.add(last);
        }
        for (int i = 0; i + 1 < tokens.length; i++) {
            String t1 = tail.get(i), t2 = head.get(i + 1);
            if (t1.isEmpty() || t2.length() < 2) continue;
            for (int j = 2; j <= t2.length(); j++) out.add(t1 + " " + t2.substring(0, j));
        }
        return out;
    }

    /** R의 원문에서 find를 지운 나머지의 연속 두 한자 조각, 든 행 수 기준 상위 SOURCE_PIECES개. */
    static List<String> sourcePieces(List<Row> rows, String find) {
        Map<String, Integer> count = new HashMap<>();
        for (Row r : rows) {
            Set<String> seen = new HashSet<>();
            Matcher m = CJK_RUN.matcher(r.src().replace(find, " "));
            while (m.find()) {
                String run = m.group();
                for (int i = 0; i + 2 <= run.length(); i++) seen.add(run.substring(i, i + 2));
            }
            for (String g : seen) count.merge(g, 1, Integer::sum);
        }
        return count.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(SOURCE_PIECES).map(Map.Entry::getKey).toList();
    }

    private static double dice(BitSet x, BitSet y) {
        int denom = x.cardinality() + y.cardinality();
        if (denom == 0) return 0;
        BitSet both = (BitSet) x.clone();
        both.and(y);
        return 2.0 * both.cardinality() / denom;
    }
}
