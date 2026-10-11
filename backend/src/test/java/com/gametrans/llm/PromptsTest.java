package com.gametrans.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.gametrans.llm.LlmClient.Reference;
import com.gametrans.llm.LlmClient.TranslateBatchRequest;
import com.gametrans.llm.LlmClient.TranslateRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptsTest {
    @Test
    void referenceTranslationsAreListedInTheSystemPrompt() {
        String sys = Prompts.translateSystem(new TranslateBatchRequest("CN_ONLY", null, Map.of(),
                List.of(new TranslateRow("a", "技能伤害提升至@480%@", null)),
                List.of(new Reference("技能伤害提升至@420%@", "스킬 피해 @420%@로 상승"))));
        assertThat(sys).contains("Reference translations");
        assertThat(sys).contains("技能伤害提升至@420%@ → 스킬 피해 @420%@로 상승");
    }

    @Test
    void frequentPhrasesAreListedWithCountsInTheExtractPrompt() {
        java.util.LinkedHashMap<String, Integer> freq = new java.util.LinkedHashMap<>();
        freq.put("商城", 12); freq.put("礼包", 7);
        String user = Prompts.extractUser(List.of("在商城购买礼包"), freq);
        assertThat(user).contains("Frequent phrases");
        assertThat(user).contains("- 商城 (12)").contains("- 礼包 (7)");
        assertThat(Prompts.extractUser(List.of("在商城购买礼包"), Map.of())).doesNotContain("Frequent phrases");
        assertThat(Prompts.EXTRACT_SYSTEM).contains("frequent phrases");
    }

    @Test
    void noReferencesMeansNoReferenceSection() {
        String sys = Prompts.translateSystem(new TranslateBatchRequest("CN_ONLY", null, Map.of(),
                List.of(new TranslateRow("a", "你好", null)), List.of()));
        assertThat(sys).doesNotContain("Reference translations");
    }

    @Test
    void aGlossaryNoteIsShownAsContextApartFromTheTerm() {
        String sys = Prompts.translateSystem(new TranslateBatchRequest("CN_ONLY", null, Map.of("金币", "골드 (의역: 게임 내 기본 화폐)"),
                List.of(new TranslateRow("a", "获得金币", null)), List.of()));
        assertThat(sys).contains("- 金币 → 골드 [note: 의역: 게임 내 기본 화폐]").doesNotContain("골드 (의역");
    }
}
