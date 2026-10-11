package com.gametrans.term;

import static org.assertj.core.api.Assertions.assertThat;

import com.gametrans.term.CandidateMiner.Phrase;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CandidateMinerTest {
    static final CandidateMiner miner = new CandidateMiner();

    @Test
    void ranksNounPhrasesByHowManyStringsContainThem() {
        List<Phrase> out = miner.mine(List.of(
                "在商城购买礼包可获得钻石", "商城限时折扣", "测试充值用户获得的专属物品，可以代替现金购买商城中的礼包",
                "每日登录送钻石", "英雄升级"), 100);
        assertThat(out).extracting(Phrase::text).startsWith("商城");
        assertThat(out).extracting(Phrase::text).contains("礼包", "钻石");
        assertThat(out.get(0).docFreq()).isEqualTo(3);
        // 한 문장에만 나오는 구절은 후보가 아니다
        assertThat(out).extracting(Phrase::text).doesNotContain("专属物品", "英雄升级", "登录");
    }

    @Test
    void skipsPlaceholdersMarkupNumbersAndFunctionWords() {
        List<Phrase> out = miner.mine(List.of(
                "获得{0}钻石", "<color=#FFD700>钻石</color>x%d", "钻石的数量", "钻石中", "花费100钻石"), 100);
        assertThat(out).extracting(Phrase::text).contains("钻石");
        assertThat(out).extracting(Phrase::text).noneMatch(t -> t.contains("{") || t.contains("<") || t.contains("%") || t.matches(".*\\d.*"));
        // 조사·기능어로 시작하거나 끝나는 구절, 기능어 단독은 뺀다
        assertThat(out).extracting(Phrase::text).doesNotContain("的", "钻石的", "钻石中", "的数量");
    }

    @Test
    void keepsTheLongerPhraseWhenItIsAboutAsFrequentAsItsParts() {
        List<Phrase> out = miner.mine(List.of("每日排行奖励已发放", "领取每日排行奖励", "每日排行奖励结算中"), 100);
        // 每日排行奖励(3) ⊃ 排行奖励(3)·每日排行(3)·每日·奖励·排行: 긴 쪽이 짧은 쪽의 80% 이상이면 긴 쪽만
        assertThat(out).extracting(Phrase::text).containsExactly("每日排行奖励");
    }

    @Test
    void keepsAShorterPhraseThatAlsoStandsOnItsOwn() {
        List<Phrase> out = miner.mine(List.of("每日排行奖励已发放", "领取每日排行奖励", "每日排行奖励结算中", "本周排行奖励"), 100);
        // 排行奖励(4)는 每日排行奖励(3)보다 자주 나오므로 따로 남고, 그 조각(排行·奖励)은 排行奖励에 묻힌다
        assertThat(out).extracting(Phrase::text).containsExactlyInAnyOrder("排行奖励", "每日排行奖励");
    }

    @Test
    void capsTheListAndIgnoresPhrasesLongerThanSixCharacters() {
        List<Phrase> out = miner.mine(List.of("测试充值用户获得的专属物品", "测试充值用户获得的专属物品奖励", "商城", "商城"), 1);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).text().length()).isLessThanOrEqualTo(6);
    }

    @Test
    void dropsPhrasesWithAnEdgeOnlyWordAtEitherEndButKeepsThatWordAlone() {
        List<String> texts = List.of("对目标造成伤害", "目标造成眩晕", "向目标造成灼烧", "造成暴击");
        // 기본 규칙에서는 目标造成(3)이 目标(3)을 묻는다
        assertThat(miner.mine(texts, 100)).extracting(Phrase::text).contains("目标造成");
        List<Phrase> out = miner.mine(texts, 100, 2, Set.of("造成"));
        assertThat(out).extracting(Phrase::text).contains("目标", "造成");
        assertThat(out).extracting(Phrase::text).noneMatch(t -> t.length() > 2 && (t.startsWith("造成") || t.endsWith("造成")));
    }

    @Test
    void honoursTheMinimumNumberOfStrings() {
        List<String> texts = List.of("对目标造成伤害", "目标造成眩晕", "向目标造成灼烧", "造成暴击");
        assertThat(miner.mine(texts, 100, 4, Set.of("造成"))).extracting(Phrase::text).containsExactly("造成");
    }

    @Test
    void anUnlimitedMaxReturnsEveryCandidate() {
        List<Phrase> out = miner.mine(List.of("商城折扣", "商城礼包", "领取礼包"), Integer.MAX_VALUE);
        assertThat(out).extracting(Phrase::text).containsExactlyInAnyOrder("商城", "礼包");
    }
}
