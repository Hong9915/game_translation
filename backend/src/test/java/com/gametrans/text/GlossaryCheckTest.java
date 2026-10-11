package com.gametrans.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GlossaryCheckTest {
    Map<String, String> glossary() {
        Map<String, String> g = new LinkedHashMap<>();
        g.put("火焰", "화염");
        g.put("商城", "인게임 상점");
        g.put("礼包", "기프트 패키지");
        return g;
    }

    @Test
    void reportsTermsPresentInSourceButAbsentFromKorean() {
        assertThat(GlossaryCheck.missing("在商城购买礼包", "상점에서 기프트 패키지 구매", glossary()))
                .containsExactly("商城→인게임 상점");
    }

    @Test
    void passesWhenEveryRelevantTermIsRendered() {
        assertThat(GlossaryCheck.missing("在商城购买礼包", "인게임 상점에서 기프트 패키지 구매", glossary())).isEmpty();
        // 원문에 없는 용어는 보지 않는다
        assertThat(GlossaryCheck.missing("一把长剑", "장검 한 자루", glossary())).isEmpty();
        assertThat(GlossaryCheck.missing("在商城购买礼包", null, glossary())).isEmpty();
    }

    @Test
    void roundTripsThroughTheStoredColumnFormat() {
        var miss = GlossaryCheck.missing("在商城购买礼包", "구매", glossary());
        assertThat(GlossaryCheck.parse(GlossaryCheck.format(miss))).isEqualTo(miss);
        assertThat(GlossaryCheck.format(java.util.List.of())).isNull();
        assertThat(GlossaryCheck.parse(null)).isEmpty();
    }

    @Test
    void aShortTermInsideALongerTermIsNotRequiredSeparately() {
        Map<String, String> g = new LinkedHashMap<>();
        g.put("城堡", "성(요새)");
        g.put("先锋榜", "선두 랭킹");
        g.put("黑暗城堡先锋榜", "암흑 성 선봉 랭킹");
        assertThat(GlossaryCheck.applicable("你在黑暗城堡先锋榜中排名第{0}", g)).containsOnlyKeys("黑暗城堡先锋榜");
        assertThat(GlossaryCheck.missing("你在黑暗城堡先锋榜中排名第{0}", "암흑 성 선봉 랭킹 {0}위", g)).isEmpty();
        // 긴 용어 밖에서 한 번 더 나오면 짧은 용어도 따로 요구한다
        assertThat(GlossaryCheck.applicable("城堡：黑暗城堡先锋榜", g)).containsOnlyKeys("城堡", "黑暗城堡先锋榜");
        assertThat(GlossaryCheck.missing("城堡：黑暗城堡先锋榜", "암흑 성 선봉 랭킹", g)).containsExactly("城堡→성(요새)");
    }

    @Test
    void aLabeledNoteAfterTheConfirmedKoreanIsNotPartOfTheTerm() {
        assertThat(GlossaryCheck.koOf("골드 (의역: 게임 내 기본 화폐)")).isEqualTo("골드");
        assertThat(GlossaryCheck.noteOf("골드 (의역: 게임 내 기본 화폐)")).isEqualTo("의역: 게임 내 기본 화폐");
        assertThat(GlossaryCheck.koOf("연성/강화（의역：캐릭터/장비 훈련）")).isEqualTo("연성/강화");
        // 라벨 없는 괄호·문장 속 괄호·자리표시자 괄호는 번역의 일부다
        for (String keep : new String[] { "성(요새)", "설정표에 서버가 보낸 상품(shopId: ${key}) 없음", "퀘스트 목표: {0}(<color=#e65f5e>{1}</color>)", "(의역: 설명뿐)" }) {
            assertThat(GlossaryCheck.koOf(keep)).isEqualTo(keep);
            assertThat(GlossaryCheck.noteOf(keep)).isNull();
        }
        assertThat(GlossaryCheck.koOf(null)).isNull();

        Map<String, String> g = Map.of("金币", "골드 (의역: 게임 내 기본 화폐)");
        assertThat(GlossaryCheck.applicable("获得金币", g)).containsEntry("金币", "골드");
        assertThat(GlossaryCheck.missing("获得金币", "골드 획득", g)).isEmpty();
        assertThat(GlossaryCheck.missing("获得金币", "코인 획득", g)).containsExactly("金币→골드");
    }

    /** 앞 숫자(+단위 한 글자)나 뒤 숫자만 다른 용어는 기본 단어로 접는다. 2글자 미만이 남거나 숫자가 없으면 변형이 아니다. */
    @Test
    void collapsesNumberedVariantsToTheirStem() {
        assertThat(GlossaryCheck.numericStem("11星英雄")).isEqualTo("星英雄");
        assertThat(GlossaryCheck.numericStem("110级史诗装备自选宝箱")).isEqualTo("史诗装备自选宝箱");
        assertThat(GlossaryCheck.numericStem("1级钻石秘境")).isEqualTo("钻石秘境");
        assertThat(GlossaryCheck.numericStem("1280钻石")).isEqualTo("钻石");
        assertThat(GlossaryCheck.numericStem("2周年庆典")).isEqualTo("周年庆典");
        assertThat(GlossaryCheck.numericStem("英雄12")).isEqualTo("英雄");
        assertThat(GlossaryCheck.numericStem("１２星英雄")).isEqualTo("星英雄");   // 전각 숫자
        assertThat(GlossaryCheck.numericStem("第3章")).isNull();               // 1글자만 남음
        assertThat(GlossaryCheck.numericStem("星英雄")).isNull();              // 숫자 없음
        assertThat(GlossaryCheck.numericStem("史诗110级装备")).isNull();       // 가운데 숫자는 다루지 않음
        assertThat(GlossaryCheck.numericStem("3D模型")).isNull();              // 숫자 뒤가 한자가 아니면 이름의 일부
        assertThat(GlossaryCheck.numericStem("5v5竞技场")).isNull();
        assertThat(GlossaryCheck.numericStem("第3关BOSS")).isNull();
        assertThat(GlossaryCheck.numericStem("12")).isNull();
        assertThat(GlossaryCheck.numericStem(null)).isNull();
    }
}
