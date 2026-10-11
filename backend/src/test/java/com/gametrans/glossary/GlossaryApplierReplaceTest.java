package com.gametrans.glossary;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** replacePartial이 치환 자리 뒤 조사를 새 표기 받침에 맞추고, 멱등 처리는 그대로인지(설계 2026-10-07 §4). */
class GlossaryApplierReplaceTest {
    @Test
    void adjustsParticleOnPlainPath() {
        assertThat(GlossaryApplier.replacePartial("아이스필드가 열림", "아이스필드", "설원")).isEqualTo("설원이 열림");
    }

    @Test
    void idempotentPathStillIdempotentAndAdjusts() {
        String once = GlossaryApplier.replacePartial("심판은 왔다. 심판관이 봤다", "심판", "심판관");
        assertThat(once).isEqualTo("심판관은 왔다. 심판관이 봤다");
        assertThat(GlossaryApplier.replacePartial(once, "심판", "심판관")).isEqualTo(once);
        assertThat(GlossaryApplier.replacePartial("검사가 왔다", "검사", "검객")).isEqualTo("검객이 왔다");
    }
}
