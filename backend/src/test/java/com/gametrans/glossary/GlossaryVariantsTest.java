package com.gametrans.glossary;

import static org.assertj.core.api.Assertions.assertThat;

import com.gametrans.glossary.GlossaryDtos.Variant;
import com.gametrans.glossary.GlossaryDtos.VariantRendering;
import com.gametrans.glossary.GlossarySuggester.Row;
import com.gametrans.term.CandidateMiner;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 손으로 만든 행 묶음. jieba 분절에 기대는 부분이 있어서 구절 목록 전체를 단정하지 않고, 보고 싶은 구절 하나를 골라 본다.
 * 분절이 달라 테스트가 깨지면 규칙이 아니라 행 묶음을 고친다.
 */
class GlossaryVariantsTest {
    static final CandidateMiner miner = new CandidateMiner();

    static final List<Row> RANDOM = List.of(
            new Row("随机宝箱", "무작위 상자"), new Row("随机奖励", "무작위 보상"), new Row("随机英雄", "무작위 영웅"), new Row("随机碎片", "무작위 조각"),
            new Row("随机技能", "랜덤 스킬"), new Row("随机装备", "랜덤 장비"), new Row("随机道具", "랜덤 아이템"));

    static final List<Row> TRAP = List.of(
            new Row("放置陷阱", "함정을 설치"), new Row("解除陷阱", "함정을 해제"), new Row("躲避陷阱", "함정을 피하기"), new Row("发现陷阱", "함정을 발견"),
            new Row("陷阱伤害", "트랩 피해"), new Row("陷阱强化", "트랩 강화"));

    static final List<Row> BOAR = List.of(
            new Row("击败野猪王", "멧돼지왕 처치"), new Row("野猪王出现", "멧돼지왕 등장"), new Row("野猪王宝箱", "멧돼지왕 상자"),
            new Row("野猪王的愤怒", "멧돼지 왕의 분노"),
            new Row("野猪冲锋", "멧돼지 돌진"), new Row("小野猪", "작은 멧돼지"), new Row("野猪群", "멧돼지 무리"));

    static final List<Row> SKILL = List.of(
            new Row("技能升级", "스킬 강화"), new Row("技能冷却", "스킬 쿨타임"), new Row("释放技能", "스킬 사용"), new Row("技能伤害", "스킬 피해"),
            new Row("被动技能", "패시브 기술"));

    private static List<Variant> find(List<Row> rows) { return GlossaryVariants.find(miner, rows, Set.of()); }

    private static Optional<Variant> of(List<Variant> out, String find) { return out.stream().filter(v -> v.find().equals(find)).findFirst(); }

    @Test
    void findsAnotherRenderingUsedOnSeveralRows() {
        Variant v = of(find(RANDOM), "随机").orElseThrow();
        assertThat(v.kind()).isEqualTo("ALT");
        assertThat(v.rows()).isEqualTo(7);
        assertThat(v.main().ko()).isEqualTo("무작위");   // "무작"으로 잘리지 않는다
        assertThat(v.main().rows()).isEqualTo(4);
        assertThat(v.main().examples()).hasSize(2);
        assertThat(v.alts()).extracting(VariantRendering::ko).containsExactly("랜덤");
        assertThat(v.alts().get(0).rows()).isEqualTo(3);
        assertThat(v.alts().get(0).spacing()).isFalse();
        assertThat(v.alts().get(0).examples()).extracting(GlossaryDtos.VariantExample::ko).allMatch(k -> k.contains("랜덤"));
        assertThat(v.missingRows()).isZero();
    }

    @Test
    void stopsTheMainRenderingBeforeAParticle() {
        Variant v = of(find(TRAP), "陷阱").orElseThrow();
        assertThat(v.main().ko()).isEqualTo("함정");     // "함정을"이 아니다
        assertThat(v.alts()).extracting(VariantRendering::ko).containsExactly("트랩");
    }

    @Test
    void findsRowsThatDifferOnlyInSpacingWithTheFormActuallyUsed() {
        Variant v = of(find(BOAR), "野猪王").orElseThrow();
        assertThat(v.kind()).isEqualTo("SPACING");
        assertThat(v.main().ko()).isEqualTo("멧돼지왕");
        assertThat(v.alts()).singleElement().satisfies(a -> {
            assertThat(a.ko()).isEqualTo("멧돼지 왕");
            assertThat(a.spacing()).isTrue();
            assertThat(a.rows()).isEqualTo(1);
        });
        assertThat(of(find(BOAR), "野猪")).isEmpty();   // 멧돼지로 한 표기
    }

    @Test
    void reportsRowsMissingAClearMainRenderingWhenTheOtherFormIsUsedOnlyOnce() {
        Variant v = of(find(SKILL), "技能").orElseThrow();
        assertThat(v.kind()).isEqualTo("MISSING");
        assertThat(v.main().ko()).isEqualTo("스킬");
        assertThat(v.alts()).isEmpty();                 // "기술"은 게임 전체에서 1행이라 이름 붙은 다른 표기가 아니다
        assertThat(v.missingRows()).isEqualTo(1);
        assertThat(v.missing()).singleElement().satisfies(e -> assertThat(e.src()).isEqualTo("被动技能"));
    }

    @Test
    void leavesOutPhrasesWithoutAFixedTranslation() {
        List<Row> rows = List.of(new Row("请注意查收", "확인해 주세요"), new Row("注意安全", "안전에 유의"), new Row("注意事项", "주의 사항"),
                new Row("注意时间", "시간을 확인"), new Row("注意躲避", "피하도록 조심"));
        assertThat(of(find(rows), "注意")).isEmpty();
    }

    @Test
    void conjugatedVerbsAreNotOtherRenderings() {
        List<Row> rows = List.of(new Row("达到等级", "등급 달성"), new Row("达到目标", "목표 달성"), new Row("达到上限", "상한 달성"),
                new Row("达到条件", "조건 달성"), new Row("伤害达到一万", "피해가 만에 도달했습니다"), new Row("达到满级", "만렙에 도달했습니다"));
        assertThat(of(find(rows), "达到")).isEmpty();
    }

    @Test
    void renderingsOfANeighbouringWordAreNotOtherRenderings() {
        List<Row> rows = List.of(new Row("自身攻击", "자신 공격"), new Row("自身防御", "자신 방어"), new Row("自身速度", "자신 속도"), new Row("自身生命", "자신 생명"),
                new Row("投掷砍刀并回到自身", "마체테를 던지고 돌아옴"), new Row("投掷砍刀攻击自身周围", "마체테를 던지고 공격"),
                new Row("投掷砍刀", "마체테를 던지기"));
        assertThat(of(find(rows), "自身")).isEmpty();
    }

    @Test
    void leavesOutPhrasesAlreadyInTheGlossary() {
        assertThat(of(GlossaryVariants.find(miner, RANDOM, Set.of("随机")), "随机")).isEmpty();
    }

    @Test
    void clipsLongExamples() {
        List<Row> rows = new ArrayList<>();
        for (Row r : RANDOM) rows.add(new Row(r.src() + "很长的说明文字".repeat(40), r.ko()));
        Variant v = of(find(rows), "随机").orElseThrow();
        assertThat(v.alts().get(0).examples().get(0).src()).hasSize(GlossaryRecommender.EXAMPLE_MAX + 1).endsWith("…");
    }

    @Test
    void aSentenceRepeatedAcrossFilesIsJudgedOnceButCountedForEveryRow() {
        List<Row> rows = new ArrayList<>(RANDOM);
        rows.add(new Row("随机礼包", "무작위 꾸러미"));
        for (int i = 0; i < 5; i++) rows.add(new Row("随机召唤", "랜덤 소환"));   // 파일마다 같은 문장 — 5번 세면 랜덤이 주 표기가 된다
        Variant v = of(find(rows), "随机").orElseThrow();
        assertThat(v.kind()).isEqualTo("ALT");
        assertThat(v.main().ko()).isEqualTo("무작위");
        assertThat(v.alts()).extracting(VariantRendering::ko).containsExactly("랜덤");
        assertThat(v.rows()).isEqualTo(13);                  // 보고하는 수는 실제 행 수(통일 창의 바뀔 행 수와 맞는다)
        assertThat(v.main().rows()).isEqualTo(5);
        assertThat(v.alts().get(0).rows()).isEqualTo(8);
        assertThat(v.alts().get(0).examples()).extracting(GlossaryDtos.VariantExample::src).doesNotHaveDuplicates().hasSize(4);
    }

    @Test
    void ignoresRowsWithANullKorean() {
        List<Row> rows = new ArrayList<>(RANDOM);
        rows.add(new Row("随机礼包", null));
        Variant v = of(find(rows), "随机").orElseThrow();
        assertThat(v.kind()).isEqualTo("ALT");
        assertThat(v.main().ko()).isEqualTo("무작위");
        assertThat(v.alts()).extracting(VariantRendering::ko).containsExactly("랜덤");
    }
}
