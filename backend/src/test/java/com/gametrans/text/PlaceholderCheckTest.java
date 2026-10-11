package com.gametrans.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlaceholderCheckTest {
    @Test void samePlaceholdersPass() {
        assertThat(PlaceholderCheck.matches("使用后恢复{0}点生命值，冷却{1}秒。", "사용 시 HP {0} 회복. 재사용 대기 {1}초.")).isTrue();
    }
    @Test void missingPlaceholderFails() {
        assertThat(PlaceholderCheck.matches("恢复{0}点", "회복")).isFalse();
    }
    @Test void tagsMustMatch() {
        assertThat(PlaceholderCheck.matches("<color=#FFD700>传说</color> 品质装备", "<color=#FFD700>전설</color> 등급 장비")).isTrue();
        assertThat(PlaceholderCheck.matches("<color=#FFD700>传说</color> 品质装备", "전설 등급 장비")).isFalse();
    }
    @Test void nullOrPlainTextPasses() {
        assertThat(PlaceholderCheck.matches("烈焰之剑", "화염의 검")).isTrue();
        assertThat(PlaceholderCheck.matches(null, "x")).isTrue();
    }
    @Test void plainComparisonTextIsNotATag() {
        assertThat(PlaceholderCheck.matches("攻击 < 防御 > 速度", "공격 < 방어 > 속도")).isTrue();
        assertThat(LengthCheck.count("a < b > c")).isEqualTo(5);
    }
}
