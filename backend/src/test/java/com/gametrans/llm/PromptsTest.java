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

    @Test
    void draftTermsPutsCategoryRuleAndKnownTerms() {
        String user = Prompts.draftTermsUser(new LlmClient.TermDraftRequest("CHARACTER", List.of("森之召唤师"),
                List.of(new Reference("陷阱", "함정")), List.of("짧게")));
        assertThat(user).startsWith("Decide first whether each term is a personal name or a title/epithet.");
        assertThat(user).contains("Already decided in this game (keep naming consistent with these):\n- 陷阱 → 함정");
        assertThat(user).contains("Game style notes:\n- 짧게");
        assertThat(user).contains("- 森之召唤师");
    }

    @Test
    void draftTermsUsesTheCategoryRuleAndSkipsEmptySections() {
        String user = Prompts.draftTermsUser(new LlmClient.TermDraftRequest("CURRENCY", List.of("金"), List.of(), List.of()));
        assertThat(user).startsWith("Prefer the established Korean game term (钻石 → 다이아, 公会 → 길드, 体力 → 스태미나).");
        assertThat(user).doesNotContain("Already decided").doesNotContain("Game style notes");
        assertThat(Prompts.draftTermsUser(new LlmClient.TermDraftRequest("MONSTER", List.of("暗影狼王"), List.of(), List.of())))
                .startsWith("Translate by meaning, short and natural, the way Korean mobile games name them.");
    }
}
