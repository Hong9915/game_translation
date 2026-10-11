package com.gametrans.glossary;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GlossaryServiceRoTest {
    @Test
    void picksRoOrEuroByTheFinalConsonant() {
        assertThat(GlossaryService.withRo("매일")).isEqualTo("로");      // ㄹ 받침
        assertThat(GlossaryService.withRo("랜덤")).isEqualTo("으로");
        assertThat(GlossaryService.withRo("트랩")).isEqualTo("으로");
        assertThat(GlossaryService.withRo("스킬 ")).isEqualTo("로");
        assertThat(GlossaryService.withRo("하의")).isEqualTo("로");
        assertThat(GlossaryService.withRo("HP")).isEqualTo("로");
    }
}
