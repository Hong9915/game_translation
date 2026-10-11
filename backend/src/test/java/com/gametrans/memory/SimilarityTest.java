package com.gametrans.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SimilarityTest {
    private static Similarity.Index index() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("烈焰之剑", "화염의 검");
        m.put("技能伤害提升至@420%@", "스킬 피해 @420%@로 상승");
        m.put("伤害减免提升至@10%@", "피해 감면 @10%@로 상승");
        m.put("对当前目标造成伤害", "현재 대상에게 피해");
        return new Similarity.Index(m);
    }

    @Test
    void sameTemplateWithDifferentNumbersScoresHighest() {
        List<Similarity.Match> top = index().top("技能伤害提升至@480%@", 2, 0.4);
        assertThat(top).isNotEmpty();
        assertThat(top.get(0).src()).isEqualTo("技能伤害提升至@420%@");
        assertThat(top.get(0).ko()).isEqualTo("스킬 피해 @420%@로 상승");
        assertThat(top.get(0).score()).isGreaterThan(0.9);
    }

    @Test
    void unrelatedTextReturnsNothingAboveThreshold() {
        assertThat(index().top("霜语者", 3, 0.4)).isEmpty();
    }

    @Test
    void partialOverlapIsRankedByJaccard() {
        List<Similarity.Match> top = index().top("烈焰之弓", 3, 0.3);
        assertThat(top).extracting(Similarity.Match::src).containsExactly("烈焰之剑");
    }

    @Test
    void emptyIndexAndBlankQueryAreSafe() {
        assertThat(new Similarity.Index(Map.of()).top("烈焰之剑", 3, 0.4)).isEmpty();
        assertThat(index().top("", 3, 0.4)).isEmpty();
        assertThat(index().top(null, 3, 0.4)).isEmpty();
    }
}
