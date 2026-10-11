package com.gametrans.memory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 문자 바이그램 자카드 유사도로 비슷한 원문을 찾는다. 숫자는 모두 같은 글자로 바꿔서
 * "@420%@"와 "@480%@"처럼 수치만 다른 문장이 거의 1.0으로 나오게 한다.
 */
public final class Similarity {
    private Similarity() {}

    public record Match(String src, String ko, double score) {}

    public static final class Index {
        private final List<String> srcs = new ArrayList<>();
        private final List<String> kos = new ArrayList<>();
        private final List<Set<String>> grams = new ArrayList<>();
        private final Map<String, List<Integer>> postings = new HashMap<>();

        public Index(Map<String, String> entries) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                int id = srcs.size();
                Set<String> g = bigrams(e.getKey());
                if (g.isEmpty()) continue;
                srcs.add(e.getKey());
                kos.add(e.getValue());
                grams.add(g);
                for (String b : g) postings.computeIfAbsent(b, k -> new ArrayList<>()).add(id);
            }
        }

        public int size() { return srcs.size(); }

        /** 점수가 minScore 이상인 항목을 높은 순으로 최대 k개. */
        public List<Match> top(String query, int k, double minScore) {
            Set<String> q = bigrams(query);
            if (q.isEmpty() || srcs.isEmpty()) return List.of();
            Map<Integer, Integer> overlap = new HashMap<>();
            for (String b : q) {
                List<Integer> ids = postings.get(b);
                if (ids == null) continue;
                for (int id : ids) overlap.merge(id, 1, Integer::sum);
            }
            List<Match> out = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : overlap.entrySet()) {
                int id = e.getKey();
                int inter = e.getValue();
                double score = (double) inter / (q.size() + grams.get(id).size() - inter);
                if (score >= minScore) out.add(new Match(srcs.get(id), kos.get(id), score));
            }
            out.sort((a, b) -> Double.compare(b.score(), a.score()));
            return out.size() > k ? new ArrayList<>(out.subList(0, k)) : out;
        }
    }

    static Set<String> bigrams(String s) {
        if (s == null) return Set.of();
        String n = normalize(s);
        Set<String> out = new HashSet<>();
        if (n.length() == 1) out.add(n);
        for (int i = 0; i + 1 < n.length(); i++) out.add(n.substring(i, i + 2));
        return out;
    }

    static String normalize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isWhitespace(c)) continue;
            sb.append(Character.isDigit(c) ? '0' : c);
        }
        return sb.toString();
    }
}
