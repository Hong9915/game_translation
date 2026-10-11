package com.gametrans.glossary;

import static org.assertj.core.api.Assertions.assertThat;

import com.gametrans.glossary.GlossaryDtos.Recommendation;
import com.gametrans.glossary.GlossaryDtos.Rendering;
import com.gametrans.glossary.GlossarySuggester.Row;
import com.gametrans.term.CandidateMiner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GlossaryRecommenderTest {
    static final CandidateMiner miner = new CandidateMiner();
    static final List<Row> PACKS = List.of(
            new Row("领取礼包", "패키지 수령"), new Row("超值礼包", "초특가 패키지"), new Row("礼包已售罄", "패키지 매진"),
            new Row("每日礼包", "일일 패키지"), new Row("新手礼包", "초보자 패키지"));

    private static List<Recommendation> recommend(List<Row> rows) { return GlossaryRecommender.recommend(miner, rows, Set.of(), Map.of()); }

    @Test
    void ranksPhrasesByHowManyDistinctSentencesContainThem() {
        List<Recommendation> out = recommend(PACKS);
        assertThat(out).extracting(Recommendation::find).containsExactly("礼包");
        assertThat(out.get(0).sentences()).isEqualTo(5);
    }

    @Test
    void attachesTheCurrentKoreanRenderingWithAnExample() {
        Recommendation r = recommend(PACKS).get(0);
        assertThat(r.rows()).isEqualTo(5);
        assertThat(r.renderings()).containsExactly(new Rendering("패키지", 5, "领取礼包", "패키지 수령"));
    }

    @Test
    void countsRowsThatDifferOnlyInNumbersAsOneSentenceButKeepsTheirRows() {
        List<Row> rows = new ArrayList<>(PACKS);
        for (int i = 1; i <= 50; i++) rows.add(new Row("神树试炼第" + i + "关", "신수 시련 " + i + "단계"));
        rows.add(new Row("新手礼包2", "초보자 패키지 2"));
        rows.add(new Row("新手礼包3", "초보자 패키지 3"));
        List<Recommendation> out = recommend(rows);
        // 第1关~第50关은 한 문장이라 5문장 기준에 못 미친다. 新手礼包2·新手礼包3도 한 문장(여섯 번째)이지만 행으로는 둘이다
        assertThat(out).extracting(Recommendation::find).containsExactly("礼包");
        assertThat(out.get(0).sentences()).isEqualTo(6);
        assertThat(out.get(0).rows()).isEqualTo(7);
    }

    @Test
    void needsFiveDistinctSentences() {
        assertThat(recommend(PACKS.subList(0, 4))).isEmpty();
    }

    @Test
    void leavesOutPhrasesAlreadyInTheGlossary() {
        assertThat(GlossaryRecommender.recommend(miner, PACKS, Set.of("礼包"), Map.of())).isEmpty();
    }

    @Test
    void dropsVerbGluedPhrasesButKeepsTheNounAndTheVerb() {
        List<Row> rows = List.of(new Row("对目标造成伤害", "대상에게 피해"), new Row("目标造成眩晕", "대상에게 기절"), new Row("向目标造成灼烧", "대상에게 화상"),
                new Row("使目标造成混乱", "대상에게 혼란"), new Row("目标造成减速", "대상에게 감속"));
        assertThat(recommend(rows)).extracting(Recommendation::find).containsExactlyInAnyOrder("目标", "造成");
    }

    @Test
    void carriesTheOldPipelineTermTranslationWhenThereIsOne() {
        List<Recommendation> out = GlossaryRecommender.recommend(miner, PACKS, Set.of(), Map.of("礼包", "선물 꾸러미"));
        assertThat(out.get(0).termKo()).isEqualTo("선물 꾸러미");
        assertThat(recommend(PACKS).get(0).termKo()).isNull();
    }

    @Test
    void clipsLongExamples() {
        String tail = "很长的说明文字".repeat(40);
        List<Row> rows = new ArrayList<>();
        for (Row r : PACKS) rows.add(new Row(r.src() + tail, r.ko() + " 아주 긴 설명".repeat(40)));
        Rendering top = recommend(rows).stream().filter(r -> r.find().equals("礼包")).findFirst().orElseThrow().renderings().get(0);
        assertThat(top.exampleSrc()).hasSize(GlossaryRecommender.EXAMPLE_MAX + 1).endsWith("…");
        assertThat(top.exampleKo()).hasSize(GlossaryRecommender.EXAMPLE_MAX + 1).endsWith("…");
    }
}
