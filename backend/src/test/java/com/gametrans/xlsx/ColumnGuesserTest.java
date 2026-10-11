package com.gametrans.xlsx;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ColumnGuesserTest {
    @Test
    void guessesByHeaderTokens() {
        ColumnGuesser.Guess g = ColumnGuesser.guess(List.of("ItemID", "Type", "CN_Text", "EN_Text"));
        assertThat(g.src()).isEqualTo("C");
        assertThat(g.en()).isEqualTo("D");
        assertThat(g.key()).isEqualTo("A");
    }

    @Test
    void guessesChineseHeaders() {
        ColumnGuesser.Guess g = ColumnGuesser.guess(List.of("key", "简体中文", "English"));
        assertThat(g.src()).isEqualTo("B");
        assertThat(g.en()).isEqualTo("C");
        assertThat(g.key()).isEqualTo("A");
    }

    /** 실제 게임 시트 헤더: 文本ID / 中文 / 英文 / 繁体中文 순. 繁体中文을 원문으로 잡으면 안 된다. */
    @Test
    void guessesChineseGameSheetHeaders() {
        ColumnGuesser.Guess g = ColumnGuesser.guess(List.of("文本ID", "文本备注说明", "中文", "英文", "日语", "繁体中文", "韩语"));
        assertThat(g.key()).isEqualTo("A");
        assertThat(g.src()).isEqualTo("C");
        assertThat(g.en()).isEqualTo("D");
    }

    @Test
    void returnsNullWhenNothingMatches() {
        ColumnGuesser.Guess g = ColumnGuesser.guess(List.of("Foo", "Bar"));
        assertThat(g.src()).isNull();
        assertThat(g.en()).isNull();
        assertThat(g.key()).isNull();
    }

    /** SkillExplain 형태: 이름·설명 두 묶음이 _CN/_EN/_ES/_KR 접미사로 나뉘어 있다 → 열 쌍 2개. */
    @Test
    void guessesColumnPairsBySuffix() {
        ColumnGuesser.Guess g = ColumnGuesser.guess(List.of("技能编号", "技能名称_CN", "技能名称_EN", "技能名称_ES", "技能名称_KR", "技能说明_CN", "技能说明_EN", "技能说明_ES", "技能说明_KR"));
        assertThat(g.key()).isEqualTo("A");
        assertThat(g.pairs()).containsExactly(new ColumnGuesser.PairGuess("B", "C", "E"), new ColumnGuesser.PairGuess("F", "G", "I"));
        assertThat(g.src()).isEqualTo("B");
        assertThat(g.en()).isEqualTo("C");
    }

    @Test
    void prefixFormAndBareHeadersMakeASinglePair() {
        assertThat(ColumnGuesser.guess(List.of("ItemID", "Type", "CN_Text", "EN_Text")).pairs())
                .containsExactly(new ColumnGuesser.PairGuess("C", "D", null));
        assertThat(ColumnGuesser.guess(List.of("房间ID", "房间名称", "房间名称KR")).pairs()).isEmpty();
    }

    /** 繁体中文은 두 번째 원문 쌍이 아니다. 韩语는 첫 쌍의 한국어 열이다. */
    @Test
    void traditionalChineseIsNotASecondPair() {
        ColumnGuesser.Guess g = ColumnGuesser.guess(List.of("文本ID", "文本备注说明", "中文", "英文", "日语", "繁体中文", "韩语"));
        assertThat(g.pairs()).containsExactly(new ColumnGuesser.PairGuess("C", "D", "G"));
    }
}
