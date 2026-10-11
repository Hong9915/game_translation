package com.gametrans.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NormalizeSrcMarkerTest {
    @Test
    void markerSourceWithRealKoreanIsGameText() {
        assertThat(NormalizeService.srcOrNull("无", "없음")).isEqualTo("无");
        assertThat(NormalizeService.srcOrNull("暂无", "없음")).isEqualTo("暂无");
    }

    @Test
    void markerSourceWithoutKoreanStaysEmpty() {
        assertThat(NormalizeService.srcOrNull("none", "None")).isNull();
        assertThat(NormalizeService.srcOrNull("无", null)).isNull();
        assertThat(NormalizeService.srcOrNull(" ", "없음")).isNull();
        assertThat(NormalizeService.srcOrNull("每日任务", null)).isEqualTo("每日任务");
    }
}
