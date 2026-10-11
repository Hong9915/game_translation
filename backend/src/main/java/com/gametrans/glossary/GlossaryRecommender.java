package com.gametrans.glossary;

import com.gametrans.glossary.GlossaryDtos.Recommendation;
import com.gametrans.glossary.GlossaryDtos.Rendering;
import com.gametrans.term.CandidateMiner;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 단어장에 넣을 만한 원문 구절을 GPT 없이 고르고, 그 구절의 지금 번역을 붙인다. 게임의 원문에서 여러 문장에 되풀이되는
 * 구절을 {@link CandidateMiner}로 뽑아 문장 수 순으로 전부 돌려주고(상한 없음), 구절마다 {@link GlossarySuggester}로
 * 같은 행의 한국어에서 지금 표기를 찾는다. 행 색인({@link GlossarySuggester.Corpus})을 구절끼리 나눠 써서
 * 게임 3(5만 5천 행, 구절 2천여 개)이 2초 안쪽이다.
 * 품사나 일반 빈도로는 거르지 않는다: 伤害·攻击·召唤은 사전에서 동사·형용사이고 活动·任务는 흔한 말이지만
 * 게임에서는 꼭 맞춰야 하는 용어다(게임 3 실측). DB에 기대지 않는 순수 함수다.
 */
public final class GlossaryRecommender {
    private GlossaryRecommender() {}

    /** 후보가 되려면 서로 다른 문장 몇 개에 나와야 하는지. */
    static final int MIN_SENTENCES = 5;
    /** 예문은 화면에서 한두 줄로 보면 충분하다 — 수천 줄을 한 번에 보내므로 긴 문장은 자른다. */
    static final int EXAMPLE_MAX = 160;

    /** 第1关·第2关처럼 숫자만 다른 행이 순위를 부풀리지 않게 한 문장으로 묶는다. */
    private static final Pattern DIGITS = Pattern.compile("[0-9０-９]+");
    /** 혼자서는 용어일 수 있지만 다른 낱말에 붙으면 이음 구절이 되는 동사(目标造成·概率提升·用于森林探险). */
    static final Set<String> GLUE_VERBS = Set.of(
            "造成", "附加", "提升", "提高", "增加", "降低", "减少", "获得", "获取", "用于", "进行", "使用", "达到", "即可");

    /** existingFinds = 이미 단어장에 원문으로 등록된 문자열, termKo = 옛 파이프라인 용어집의 원문 → 번역. */
    public static List<Recommendation> recommend(CandidateMiner miner, List<GlossarySuggester.Row> rows,
                                                 Set<String> existingFinds, Map<String, String> termKo) {
        Set<String> sentences = new LinkedHashSet<>();
        for (GlossarySuggester.Row r : rows) if (r.src() != null && !r.src().isBlank()) sentences.add(DIGITS.matcher(r.src()).replaceAll("0"));
        GlossarySuggester.Corpus corpus = GlossarySuggester.corpus(rows);
        List<Recommendation> out = new ArrayList<>();
        for (CandidateMiner.Phrase p : miner.mine(new ArrayList<>(sentences), Integer.MAX_VALUE, MIN_SENTENCES, GLUE_VERBS)) {
            if (existingFinds.contains(p.text())) continue;
            GlossarySuggester.Result now = corpus.suggest(p.text(), null);
            List<Rendering> renderings = now.candidates().stream()
                    .map(c -> new Rendering(c.oldKo(), c.rows(), clip(c.example().src()), clip(c.example().before()))).toList();
            out.add(new Recommendation(p.text(), p.docFreq(), now.rows(), renderings, termKo.get(p.text())));
        }
        return out;
    }

    static String clip(String s) { return s.length() <= EXAMPLE_MAX ? s : s.substring(0, EXAMPLE_MAX) + "…"; }
}
