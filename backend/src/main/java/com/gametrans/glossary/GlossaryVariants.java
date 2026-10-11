package com.gametrans.glossary;

import com.gametrans.glossary.GlossaryDtos.Variant;
import com.gametrans.glossary.GlossaryDtos.VariantExample;
import com.gametrans.glossary.GlossaryDtos.VariantRendering;
import com.gametrans.glossary.GlossarySuggester.Row;
import com.gametrans.term.CandidateMiner;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 같은 중국어 구절이 다른 한국어로 옮겨진 곳을 찾는다(설계 2026-10-07 §1). 추천과 같은 재료 — 구절은 {@link CandidateMiner},
 * "어떤 문자열이 든 행"은 {@link GlossarySuggester.Corpus}, 한국어 후보는 {@link GlossarySuggester#candidatesOf}.
 *
 * 구절 p마다: 원문에 p가 든 행 R에서 Dice가 가장 높은 표기를 주 표기로 고르고(잘린 꼴은 늘린다), 주 표기가 없는 행 O에서
 * (1) 띄어쓰기만 다른 꼴, (2) 거의 p와만 같이 나오고 주 표기와는 한 행에 안 나오는 다른 표기를 찾는다. 동사 활용형과
 * 이웃 낱말의 번역은 뺀다. 그래도 남는 O 행은 "주 표기 빠짐"이다. DB에 기대지 않는 순수 함수다.
 */
public final class GlossaryVariants {
    private GlossaryVariants() {}

    static final int MIN_ROWS = 3;
    static final double MAIN_SHARE = 0.5;
    static final double STRONG_SHARE = 0.8;
    static final int MIN_ALT_ROWS = 2;
    static final double MIN_PRECISION = 0.6;
    static final double EXCLUSIVE = 0.8;
    static final double FULL_FORM_SHARE = 0.9;
    static final int MAX_ALTS = 4, MAIN_EXAMPLES = 2, ALT_EXAMPLES = 5, MISSING_EXAMPLES = 8;
    /** 문장마다 바뀌는 게 정상인 동사 활용형(획득/얻을 수, 실패/못했습니다) — 다른 표기로 세지 않는다. */
    static final Pattern VERBISH = Pattern.compile("(니다|습니|했|였|하면|되면|으면|하게|르게|을 수|ㄹ 수|할 수|여라|어라|아라|세요|주세요)$");
    /** 주 표기가 이렇게 끝나면 조사가 붙은 채 잡힌 것이라 "주 표기 빠짐"을 단정하지 않는다. */
    private static final Set<String> WEAK_ENDINGS = Set.of("이", "가", "을", "를", "의", "에", "로");
    private static final Pattern CJK_RUN = Pattern.compile("[一-鿿]+");

    enum Kind { ALT, SPACING, MISSING }

    private record Alt(String ko, BitSet rows, boolean spacing) {}

    /** 행 번호는 모두 겹치지 않는 (원문, 번역) 쌍의 번호다. allRows = 원문에 find가 든 쌍. */
    private record Found(String find, Kind kind, BitSet allRows, String main, BitSet mainRows, List<Alt> alts, BitSet missing) {
        /** 갈린 쌍 수 — 같은 문장이 파일마다 여러 번 나와도 한 번으로 센다. */
        int split() { return alts.stream().mapToInt(a -> a.rows().cardinality()).sum() + missing.cardinality(); }

        /** 겹친 구절 판정용: 띄어쓰기가 아닌 다른 표기와 그 행. */
        List<List<Object>> altKey() { return alts.stream().filter(a -> !a.spacing()).map(a -> List.<Object>of(a.ko(), a.rows())).toList(); }
    }

    /**
     * existingFinds = 이미 단어장에 원문으로 등록된 문자열(빠진다). 판정과 정렬은 겹치지 않는 (원문, 번역) 쌍으로 한다 — 파일마다
     * 되풀이된 한 문장이 기준을 혼자 넘기지 않게. 보고하는 행 수는 되풀이를 모두 센 실제 행 수다. 갈린 쌍 수 많은 순.
     */
    public static List<Variant> find(CandidateMiner miner, List<Row> allRows, Set<String> existingFinds) {
        Map<Row, Integer> pairs = new LinkedHashMap<>();
        for (Row r : allRows)
            if (r.src() != null && !r.src().isBlank() && r.ko() != null) pairs.merge(r, 1, Integer::sum);
        List<Row> rows = List.copyOf(pairs.keySet());
        int[] weight = pairs.values().stream().mapToInt(Integer::intValue).toArray();
        List<String> sentences = rows.stream().map(Row::src).filter(s -> s != null && !s.isBlank()).distinct().toList();
        GlossarySuggester.Corpus corpus = GlossarySuggester.corpus(rows);
        List<Found> found = new ArrayList<>();
        for (CandidateMiner.Phrase p : miner.mine(sentences, Integer.MAX_VALUE, MIN_ROWS, GlossaryRecommender.GLUE_VERBS)) {
            if (existingFinds.contains(p.text())) continue;
            Found f = judge(corpus, rows, p.text());
            if (f != null) found.add(f);
        }
        // 겹친 구절 정리(属性额外 / 属性额外提升): 다른 표기와 그 행이 똑같으면 짧은 구절 하나만
        found.sort(Comparator.comparingInt((Found f) -> f.find().length()).thenComparing(Found::find));
        Set<List<List<Object>>> seen = new HashSet<>();
        List<Found> kept = new ArrayList<>();
        for (Found f : found) if (f.kind() != Kind.ALT || seen.add(f.altKey())) kept.add(f);
        kept.sort(Comparator.comparingInt(Found::split).reversed().thenComparing(Found::find));
        return kept.stream().map(f -> dto(f, rows, weight)).toList();
    }

    private static Found judge(GlossarySuggester.Corpus corpus, List<Row> rows, String p) {
        BitSet r = corpus.srcMask(p);
        int n = r.cardinality();
        if (n < MIN_ROWS) return null;

        Map<String, Integer> a = new HashMap<>();
        for (int i = r.nextSetBit(0); i >= 0; i = r.nextSetBit(i + 1))
            for (String c : GlossarySuggester.candidatesOf(rows.get(i).ko())) a.merge(c, 1, Integer::sum);
        String top = a.entrySet().stream().filter(e -> e.getValue() >= 2)
                .min(Comparator.comparingDouble((Map.Entry<String, Integer> e) -> -2.0 * e.getValue() / (n + corpus.koCount(e.getKey())))
                        .thenComparingInt(e -> -e.getValue()).thenComparingInt(e -> e.getKey().length()).thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey).orElse(null);
        if (top == null) return null;
        String main = fullForm(top, c -> a.getOrDefault(c, 0), a.keySet());

        String squashed = squash(main);
        Pattern loose = loose(squashed);
        BitSet mainRows = new BitSet(), o = new BitSet();
        Map<String, BitSet> spaced = new LinkedHashMap<>();
        for (int i = r.nextSetBit(0); i >= 0; i = r.nextSetBit(i + 1)) {
            String ko = rows.get(i).ko();
            if (ko.contains(main)) { mainRows.set(i); continue; }
            Matcher m = loose.matcher(ko);
            if (m.find()) spaced.computeIfAbsent(m.group(), k -> new BitSet()).set(i);
            else o.set(i);
        }
        if (mainRows.cardinality() < MAIN_SHARE * n || (o.isEmpty() && spaced.isEmpty())) return null;

        List<Alt> alts = alternatives(corpus, rows, p, r, n, o, squashed);
        BitSet missing = (BitSet) o.clone();
        for (Alt x : alts) missing.andNot(x.rows());
        List<Alt> all = new ArrayList<>(alts);
        spaced.forEach((form, rs) -> all.add(new Alt(form, rs, true)));

        Kind kind;
        if (!alts.isEmpty()) kind = Kind.ALT;
        else if (!spaced.isEmpty()) kind = Kind.SPACING;
        else if (strong(main, mainRows.cardinality(), n)) kind = Kind.MISSING;
        else return null;
        return new Found(p, kind, r, main, mainRows, all, missing);
    }

    private static List<Alt> alternatives(GlossarySuggester.Corpus corpus, List<Row> rows, String p, BitSet r, int n, BitSet o, String mainSquashed) {
        Map<String, Integer> ao = new HashMap<>();
        Map<Integer, Set<String>> pieces = new HashMap<>();
        for (int i = o.nextSetBit(0); i >= 0; i = o.nextSetBit(i + 1)) {
            for (String c : GlossarySuggester.candidatesOf(rows.get(i).ko())) ao.merge(c, 1, Integer::sum);
            pieces.put(i, bigrams(rows.get(i).src().replace(p, " ")));
        }
        List<Alt> passed = new ArrayList<>();
        for (String c : ao.keySet()) {
            if (VERBISH.matcher(c).find() || related(squash(c), mainSquashed)) continue;
            int b = corpus.koCount(c);
            if (b < MIN_ALT_ROWS) continue;
            int inR = containing(rows, r, c).cardinality();
            BitSet inO = containing(rows, o, c);
            if (inR < MIN_PRECISION * b || inO.cardinality() < EXCLUSIVE * inR) continue;   // 정밀도, 배타성
            if (explainedByNeighbour(corpus, c, b, n, inR, inO, pieces)) continue;
            String form = fullForm(c, d -> containing(rows, o, d).cardinality(), ao.keySet());
            if (VERBISH.matcher(form).find() || related(squash(form), mainSquashed)) continue;
            passed.add(new Alt(form, containing(rows, o, form), false));
        }
        passed.sort(Comparator.comparingInt((Alt x) -> -x.rows().cardinality()).thenComparingInt(x -> -x.ko().length()).thenComparing(Alt::ko));
        List<Alt> picked = new ArrayList<>();
        for (Alt x : passed) {
            if (picked.size() == MAX_ALTS) break;
            // 같은 행들을 덮는 겹친 꼴(원클릭/원클릭으로, 보라 하/하의)은 먼저 고른 것 하나만
            if (picked.stream().anyMatch(y -> covers(y.rows(), x.rows()) && overlaps(x.ko(), y.ko()))) continue;
            picked.add(x);
        }
        return picked;
    }

    /** c가 든 O 행의 원문(p를 지운 나머지)에 절반 넘게 나오는 두 글자 조각 가운데 p보다 c와 더 잘 맞는 게 있으면 이웃 낱말의 번역이다. */
    private static boolean explainedByNeighbour(GlossarySuggester.Corpus corpus, String c, int b, int n, int inR, BitSet inO,
                                                Map<Integer, Set<String>> pieces) {
        int bo = inO.cardinality();
        Map<String, Integer> m = new HashMap<>();
        for (int i = inO.nextSetBit(0); i >= 0; i = inO.nextSetBit(i + 1)) for (String g : pieces.get(i)) m.merge(g, 1, Integer::sum);
        double withFind = 2.0 * inR / (b + n);
        BitSet rowsOfC = null;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (e.getValue() * 2 <= bo) continue;
            if (rowsOfC == null) rowsOfC = corpus.koRows(c);
            BitSet g = corpus.srcMask(e.getKey());
            BitSet both = (BitSet) rowsOfC.clone();
            both.and(g);
            if (2.0 * both.cardinality() / (b + g.cardinality()) > withFind) return true;
        }
        return false;
    }

    /** 같은 행들에(90% 이상) 늘 같이 나오는 더 긴 접두사 꼴로 늘린다("무작" → "무작위"). 덧붙는 부분이 조사·어미면 그 꼴로는 안 늘린다. */
    static String fullForm(String c, ToIntFunction<String> count, Collection<String> cands) {
        while (true) {
            String cur = c;
            int base = count.applyAsInt(cur);
            String next = cands.stream()
                    .filter(d -> d.length() > cur.length() && d.startsWith(cur) && !GlossarySuggester.SUFFIXES.contains(d.substring(cur.length()).strip()))
                    .filter(d -> count.applyAsInt(d) >= FULL_FORM_SHARE * base)
                    .min(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder())).orElse(null);
            if (next == null) return cur;
            c = next;
        }
    }

    private static boolean strong(String main, int mainRows, int n) {
        return mainRows >= STRONG_SHARE * n && !main.contains(" ") && WEAK_ENDINGS.stream().noneMatch(main::endsWith);
    }

    private static boolean related(String a, String b) { return a.contains(b) || b.contains(a); }

    private static boolean covers(BitSet big, BitSet small) {
        BitSet rest = (BitSet) small.clone();
        rest.andNot(big);
        return rest.isEmpty();
    }

    private static boolean overlaps(String x, String y) {
        if (x.contains(y) || y.contains(x)) return true;
        for (String w : x.split(" ")) if (w.length() >= 2 && y.startsWith(w.substring(0, 2))) return true;
        return false;
    }

    private static BitSet containing(List<Row> rows, BitSet among, String needle) {
        BitSet out = new BitSet();
        for (int i = among.nextSetBit(0); i >= 0; i = among.nextSetBit(i + 1)) if (rows.get(i).ko().contains(needle)) out.set(i);
        return out;
    }

    private static Set<String> bigrams(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = CJK_RUN.matcher(text);
        while (m.find()) for (int i = 0; i + 2 <= m.group().length(); i++) out.add(m.group().substring(i, i + 2));
        return out;
    }

    private static String squash(String s) { return s.replaceAll("\\s+", ""); }

    /** 공백을 지운 표기의 글자 사이에 공백이 있거나 없는 꼴("멧돼지 왕", "멧돼지왕"). */
    private static Pattern loose(String squashed) {
        return Pattern.compile(squashed.codePoints().mapToObj(cp -> Pattern.quote(new String(Character.toChars(cp)))).collect(Collectors.joining("\\s*")));
    }

    /** 수는 되풀이까지 센 실제 행 수, 예문은 겹치지 않는 쌍에서. */
    private static Variant dto(Found f, List<Row> rows, int[] weight) {
        List<Integer> mainIdx = f.mainRows().stream().boxed()
                .sorted(Comparator.comparingInt((Integer i) -> rows.get(i).src().length()).thenComparingInt(i -> i)).limit(MAIN_EXAMPLES).toList();
        VariantRendering main = new VariantRendering(f.main(), weighted(f.mainRows(), weight), false, examples(rows, mainIdx));
        List<VariantRendering> alts = f.alts().stream().map(a -> new VariantRendering(a.ko(), weighted(a.rows(), weight), a.spacing(),
                examples(rows, a.rows().stream().limit(ALT_EXAMPLES).boxed().toList()))).toList();
        return new Variant(f.find(), f.kind().name(), weighted(f.allRows(), weight), main, alts, weighted(f.missing(), weight),
                examples(rows, f.missing().stream().limit(MISSING_EXAMPLES).boxed().toList()), null);
    }

    private static int weighted(BitSet pairs, int[] weight) { return pairs.stream().map(i -> weight[i]).sum(); }

    private static List<VariantExample> examples(List<Row> rows, List<Integer> idx) {
        return idx.stream().map(i -> new VariantExample(GlossaryRecommender.clip(rows.get(i).src()), GlossaryRecommender.clip(rows.get(i).ko()))).toList();
    }
}
