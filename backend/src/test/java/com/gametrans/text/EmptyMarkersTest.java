package com.gametrans.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmptyMarkersTest {
    @Test
    void blankAndNullAreEmpty() {
        assertThat(EmptyMarkers.isEmpty(null)).isTrue();
        assertThat(EmptyMarkers.isEmpty("")).isTrue();
        assertThat(EmptyMarkers.isEmpty("   ")).isTrue();
    }

    @Test
    void placeholderMarkersCountAsEmptyRegardlessOfCase() {
        for (String s : new String[] {"N/A", "n/a", "NA", "None", "none", "NULL", "-", "无", "待定", "TBD", " N/A "})
            assertThat(EmptyMarkers.isEmpty(s)).as(s).isTrue();
    }

    @Test
    void realTextIsNotEmpty() {
        for (String s : new String[] {"정의의 심판", "对当前目标造成伤害", "Rule of Law", "N/A 표시", "0", "no"})
            assertThat(EmptyMarkers.isEmpty(s)).as(s).isFalse();
    }

    @Test
    void markerDistinguishesPlaceholderFromTrulyBlank() {
        assertThat(EmptyMarkers.isMarker("N/A")).isTrue();
        assertThat(EmptyMarkers.isMarker("")).isFalse();
        assertThat(EmptyMarkers.isMarker(null)).isFalse();
        assertThat(EmptyMarkers.isMarker("정의의 심판")).isFalse();
    }
}
