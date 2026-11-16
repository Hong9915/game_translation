package com.gametrans.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.gametrans.rules.RuleText.Clause;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleTextTest {
    @Test
    void clausesByEnding() {
        assertThat(RuleText.clauses("공격력이 증가합니다. 피해 감소\n회복됨")).isEqualTo(List.of(Clause.FORMAL, Clause.NOUN, Clause.NOUN));
    }

    @Test
    void clausesIgnoreTags() {
        assertThat(RuleText.clauses("<color=#fff>2턴</color> 동안 지속됩니다</color>")).containsExactly(Clause.FORMAL);
    }

    @Test
    void questionsAreNotConvertible() {
        assertThat(RuleText.hasConvertible("계속 도전하시겠습니까?")).isFalse();
        assertThat(RuleText.hasConvertible("실패했습니다. 다시 하시겠습니까?")).isTrue();
    }
    @Test
    void bareNounEndingCountsAsNoun() {
        assertThat(RuleText.clauses("목표에 도달.")).containsExactly(Clause.NOUN);
    }

    @Test
    void haseyoAndHandaAreConvertible() {
        assertThat(RuleText.hasConvertible("버튼을 선택하세요")).isTrue();
        assertThat(RuleText.hasConvertible("3턴 동안 지속된다.")).isTrue();
        assertThat(RuleText.hasConvertible("잠시 후 다시 시도해 주세요")).isFalse();
    }
}
