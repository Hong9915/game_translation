package com.gametrans.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LengthCheckTest {
    @Test void countsExcludePlaceholdersTagsAndSpaces() {
        assertThat(LengthCheck.count("烈焰之剑")).isEqualTo(4);
        assertThat(LengthCheck.count("화염의 검")).isEqualTo(4);
        assertThat(LengthCheck.count("<color=#FFD700>전설</color> 등급 장비")).isEqualTo(6);
        assertThat(LengthCheck.count("HP {0} 회복")).isEqualTo(4);
        assertThat(LengthCheck.count(null)).isZero();
    }
    @Test void flagsWhenKoreanLongerThanRatio() {
        assertThat(LengthCheck.flagged("烈焰之剑", "화염의 검", 1.0)).isFalse();
        assertThat(LengthCheck.flagged("霜语者", "서리속삭임", 1.0)).isTrue();
        assertThat(LengthCheck.flagged("霜语者", "서리속삭임", 2.0)).isFalse();
        assertThat(LengthCheck.flagged("", "긴 문장", 1.0)).isFalse();
    }
}
