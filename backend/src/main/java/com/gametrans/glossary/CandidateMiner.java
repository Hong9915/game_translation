package com.gametrans.glossary;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 파일 전체의 원문에서 반복되는 중국어 명사구를 GPT 없이 뽑는다. 3단계 추출 프롬프트에 "이 파일에서 자주 나오는 구절"
 * 힌트로 들어가고, 최종 용어 선택은 여전히 GPT와 사람이 한다.
 * 규칙: jieba로 분절한 연속 토큰 1~3개, 합쳐서 2~6자, 한자만. 자리표시자·태그·숫자·구두점 경계는 넘지 않는다.
 * 기능어(的/了/在/中 …)는 단독으로도, 구절의 양끝으로도 쓰지 않는다. 점수는 그 구절이 들어 있는 문장 수(문서 빈도).
 */
@Component
public class CandidateMiner {
    /** 후보로 남기려면 최소 몇 개 문장에 나와야 하는지. */
    static final int MIN_DOC_FREQ = 2;
    static final int MIN_LEN = 2, MAX_LEN = 6, MAX_TOKENS = 3;
    /** 긴 구절의 빈도가 짧은 구절의 이 비율 이상이면 짧은 쪽을 버린다(商城 vs 每日商城 같은 중복 정리). */
    static final double SUBSUME_RATIO = 0.8;
    /** 중복 정리 대상 = 상한 × 이 배수. */
    static final int POOL_FACTOR = 10;

    private static final Pattern HAN = Pattern.compile("^\\p{IsHan}+$");
    private static final Pattern BOUNDARY = Pattern.compile("\\{\\d+\\}|%[sd]|</?[A-Za-z][^<>]*>|[0-9０-９]+");
    private static final Set<String> STOP = Set.of(
            "的", "了", "和", "与", "在", "中", "可以", "可", "后", "时", "是", "有", "为", "将", "会", "并", "或", "及", "每", "个", "之",
            "已", "已经", "被", "把", "让", "对", "从", "到", "向", "于", "所", "也", "都", "就", "不", "没有", "无", "该", "此", "其", "您", "你", "我",
            "一", "一个", "这", "那", "本", "当", "而", "则", "即", "等", "上", "下", "内", "外", "前", "里", "以", "至", "自", "由", "且", "但", "如", "若");

    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    public record Phrase(String text, int docFreq) {}

    public List<Phrase> mine(List<String> texts, int max) { return mine(texts, max, MIN_DOC_FREQ, Set.of()); }

    /**
     * 최소 문장 수를 바꾸고, 혼자서는 후보가 되지만 구절의 양끝에는 못 오는 낱말(edgeOnly)을 더한 채굴.
     * 단어장 추천이 쓴다: 造成은 남기고 目标造成·造成伤害 같은 이음 구절만 뺀다.
     */
    public List<Phrase> mine(List<String> texts, int max, int minDocFreq, Set<String> edgeOnly) {
        Map<String, Integer> df = new HashMap<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) continue;
            Set<String> seen = new HashSet<>();
            for (String piece : BOUNDARY.split(text)) {
                List<SegToken> tokens = segmenter.process(piece, JiebaSegmenter.SegMode.SEARCH);
                for (int i = 0; i < tokens.size(); i++) {
                    StringBuilder sb = new StringBuilder();
                    for (int n = 0; n < MAX_TOKENS && i + n < tokens.size(); n++) {
                        SegToken t = tokens.get(i + n);
                        if (!HAN.matcher(t.word).matches()) break;
                        if (n > 0 && tokens.get(i + n - 1).endOffset != t.startOffset) break;
                        sb.append(t.word);
                        if (sb.length() > MAX_LEN) break;
                        boolean edgeStop = STOP.contains(tokens.get(i).word) || STOP.contains(t.word)
                                || (n > 0 && (edgeOnly.contains(tokens.get(i).word) || edgeOnly.contains(t.word)));
                        if (sb.length() >= MIN_LEN && !edgeStop) seen.add(sb.toString());
                    }
                }
            }
            for (String p : seen) df.merge(p, 1, Integer::sum);
        }
        List<Phrase> all = new ArrayList<>();
        for (Map.Entry<String, Integer> e : df.entrySet()) if (e.getValue() >= minDocFreq) all.add(new Phrase(e.getKey(), e.getValue()));
        all.sort(Comparator.comparingInt(Phrase::docFreq).reversed().thenComparing((Phrase p) -> p.text().length(), Comparator.reverseOrder()).thenComparing(Phrase::text));
        // 중복 정리는 상위 일부끼리만 한다. 5만 행 파일이면 후보가 수만 개라 전체 쌍 비교는 너무 느리다.
        List<Phrase> pool = all.subList(0, (int) Math.min(all.size(), (long) Math.max(max, 1) * POOL_FACTOR));
        List<Phrase> kept = new ArrayList<>();
        for (Phrase p : pool) {
            boolean subsumed = false;
            for (Phrase longer : pool) {
                if (longer.text().length() > p.text().length() && longer.text().contains(p.text()) && longer.docFreq() >= SUBSUME_RATIO * p.docFreq()) { subsumed = true; break; }
            }
            if (!subsumed) kept.add(p);
            if (kept.size() >= max) break;
        }
        return kept;
    }
}
