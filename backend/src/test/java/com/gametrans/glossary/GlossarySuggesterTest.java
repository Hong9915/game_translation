package com.gametrans.glossary;

import static org.assertj.core.api.Assertions.assertThat;

import com.gametrans.glossary.GlossarySuggester.Candidate;
import com.gametrans.glossary.GlossarySuggester.Result;
import com.gametrans.glossary.GlossarySuggester.Row;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 설계 §2.2 — DB 없이 (원문, 한국어) 목록만으로 기존 표기 후보를 뽑는다. */
class GlossarySuggesterTest {
    private static Row r(String src, String ko) { return new Row(src, ko); }

    /** 升星이 计划 없이도 나오는 게임: 승급이 1순위, 계획은 计划의 번역이라 빠진다. */
    private static List<Row> upgradeGame() {
        return new ArrayList<>(List.of(
                r("升星计划", "승급 계획"),
                r("升星材料", "승급 재료"),
                r("英雄升星", "영웅 승급"),
                r("升星消耗", "승급을 위한 소모"),
                r("晋升奖励", "승급 보상"),          // 승급이지만 升星이 아니다 — 원문 조건으로 걸러져야 한다
                r("每日计划", "일일 계획"),
                r("活动计划", "이벤트 계획"),
                r("计划完成", "계획 완료"),
                r("宝箱", "보물 상자")));
    }

    private static List<String> names(Result res) { return res.candidates().stream().map(Candidate::oldKo).toList(); }

    @Test
    void topCandidateIsTheWordThatRendersTheTerm() {
        Result res = GlossarySuggester.suggest(upgradeGame(), "升星", "성급");
        assertThat(res.rows()).isEqualTo(4);
        assertThat(names(res)).startsWith("승급");
        Candidate top = res.candidates().get(0);
        assertThat(top.rows()).isEqualTo(4);
        assertThat(top.example().src()).isEqualTo("升星计划");
        assertThat(top.example().before()).isEqualTo("승급 계획");
        assertThat(top.example().after()).isEqualTo("성급 계획");
    }

    @Test
    void neighborsExplainedByAnotherSourcePieceAreDropped() {
        // 升星이 늘 计划와 함께만 나오는 게임: 통계만으로는 "승급 계획"이 1순위지만, 계획은 计划와의 점수가
        // 升星보다 높으니 计划의 번역으로 보고 "계획"·"승급 계획"을 뺀다.
        List<Row> rows = List.of(
                r("升星计划", "승급 계획"), r("开启升星计划", "승급 계획 열기"), r("升星计划完成", "승급 계획 완료"),
                r("每日计划", "일일 계획"), r("活动计划", "이벤트 계획"), r("计划完成", "계획 완료"), r("新计划", "새 계획"), r("计划书", "계획서"),
                r("晋升奖励", "승급 보상"), r("晋升", "승급"));
        Result res = GlossarySuggester.suggest(rows, "升星", "성급");
        assertThat(names(res)).startsWith("승급");
        assertThat(names(res)).doesNotContain("계획", "승급 계획");
    }

    @Test
    void particlesAreStrippedByPreferringTheShorterStem() {
        // "승급을"은 2행, "승급"은 4행 — 긴 쪽의 a가 작으니 긴 쪽(조사 붙은 꼴)을 뺀다
        List<Row> rows = List.of(
                r("升星需要材料", "승급을 하려면 재료가 필요합니다"),
                r("开始升星", "승급을 시작"),
                r("升星完成", "승급이 끝났습니다"),
                r("升星", "승급"),
                r("材料", "재료"), r("完成", "끝났습니다"));
        Result res = GlossarySuggester.suggest(rows, "升星", null);
        assertThat(names(res)).startsWith("승급");
        assertThat(names(res)).doesNotContain("승급을", "승급이");
        assertThat(res.candidates().get(0).example().after()).isNull();   // ko가 없으면 치환 예시도 없다
    }

    @Test
    void twoTokenTermBeatsItsParts() {
        // 红包 → "행운 봉투": 두 토큰이 같은 행 수로 나오면 통째로 잡는다(부분 "행운"·"봉투"는 뺀다).
        List<Row> rows = List.of(
                r("领取红包", "행운 봉투 받기"), r("红包已领完", "행운 봉투가 모두 소진되었습니다"), r("发送红包", "행운 봉투 보내기"),
                r("领取奖励", "보상 받기"), r("发送消息", "메시지 보내기"), r("幸运抽奖", "행운 뽑기"));
        Result res = GlossarySuggester.suggest(rows, "红包", "빨간 봉투");
        assertThat(names(res)).startsWith("행운 봉투");
        assertThat(names(res)).doesNotContain("행운", "봉투");
    }

    @Test
    void noRowsMeansNoCandidates() {
        Result res = GlossarySuggester.suggest(upgradeGame(), "没有的词", "없음");
        assertThat(res.rows()).isZero();
        assertThat(res.candidates()).isEmpty();
    }

    @Test
    void candidatesSeenInOnlyOneRowAreDropped() {
        // a ≥ 2: 한 행에만 나온 낱말은 후보가 아니다
        List<Row> rows = List.of(r("升星计划", "승급 계획"), r("升星材料", "승급 재료 특별"), r("材料", "재료"));
        Result res = GlossarySuggester.suggest(rows, "升星", null);
        assertThat(names(res)).doesNotContain("특별");
    }

    @Test
    void returnsAtMostThreeCandidates() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) rows.add(r("升星" + i, "가나 다라 마바 사아 자차 " + i));
        rows.add(r("其他", "기타"));
        Result res = GlossarySuggester.suggest(rows, "升星", null);
        assertThat(res.candidates()).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void ignoresNonHangulInsideTokens() {
        // Review Focus 1: 괄호·숫자·기호가 붙어도 한글 구간만 후보가 된다
        List<Row> rows = List.of(
                r("升星(1星)", "승급(1성)"), r("+10升星", "+10 승급"), r("升星→", "승급→"),
                r("其他", "기타"), r("别的", "다른 것"));
        Result res = GlossarySuggester.suggest(rows, "升星", "성급");
        assertThat(names(res)).startsWith("승급");
        assertThat(res.candidates().get(0).rows()).isEqualTo(3);
    }

    @Test
    void handlesSourceThatIsJustTheTerm() {
        // Review Focus 2: 원문이 find 자체이거나 find가 여러 번 나와도 조각 계산이 예외 없이 돈다
        List<Row> rows = List.of(r("升星", "승급"), r("升星升星", "승급 승급"), r("升星", "승급"), r("其他", "기타"));
        Result res = GlossarySuggester.suggest(rows, "升星", "성급");
        assertThat(names(res)).startsWith("승급");
    }

    @Test
    void rowsWithoutKoreanAreIgnored() {
        List<Row> rows = List.of(r("升星计划", null), r("升星材料", "승급 재료"), r("升星", "승급"), r("其他", "기타"));
        Result res = GlossarySuggester.suggest(rows, "升星", null);
        assertThat(res.rows()).isEqualTo(2);
        assertThat(names(res)).startsWith("승급");
    }

    @Test
    void wholeWordBeatsInnerFragments() {
        // 실데이터(宝箱→보물상자)에서 "물상"·"상자" 같은 낱말 속 조각이 후보로 올라왔다. 후보는 낱말의 접두사만이고,
        // 같은 행 수면 조사가 아닌 확장은 긴 쪽(온전한 낱말)을 남긴다.
        List<Row> rows = List.of(
                r("打开宝箱", "보물상자 열기"), r("宝箱奖励", "보물상자 보상"), r("获得宝箱", "보물상자 획득"), r("宝箱", "보물상자"),
                r("宝物", "보물"), r("打开", "열기"), r("奖励", "보상"));
        Result res = GlossarySuggester.suggest(rows, "宝箱", "상자");
        assertThat(names(res)).startsWith("보물상자");
        assertThat(names(res)).doesNotContain("물상", "상자", "보물상", "보물");
    }

    @Test
    void compoundWordBeatsItsPrefixWhenThePrefixIsCommonElsewhere() {
        // 晶果→수정열매: "수정"은 다른 원문(修正)에서도 쓰여 점수가 낮으니 온전한 "수정열매"가 앞선다
        List<Row> rows = List.of(
                r("晶果激活", "수정열매 활성화"), r("获得晶果", "수정열매 획득"), r("晶果碎片", "수정열매 조각"),
                r("修正错误", "오류 수정"), r("修正", "수정하기"), r("修正完成", "수정 완료"),
                r("激活", "활성화"), r("碎片", "조각"));
        Result res = GlossarySuggester.suggest(rows, "晶果", "크리스탈");
        assertThat(names(res)).startsWith("수정열매");
        assertThat(names(res)).doesNotContain("수정열", "정열", "열매");
    }

    @Test
    void particleSuffixIsStrippedEvenWhenEveryRowUsesIt() {
        // 모든 행이 "승급을"이라 a가 같아도, 남는 조각(을)이 조사면 어간 "승급"을 남긴다
        List<Row> rows = List.of(
                r("升星需要材料", "승급을 하려면 재료가 필요합니다"), r("开始升星", "승급을 시작"), r("升星完成", "승급을 완료"),
                r("材料", "재료"), r("完成", "완료"));
        Result res = GlossarySuggester.suggest(rows, "升星", null);
        assertThat(names(res)).startsWith("승급");
        assertThat(names(res)).doesNotContain("승급을");
    }

    @Test
    void twoTokenTermSurvivesNextToItsSecondWordWhenOneRowSpellsItDifferently() {
        // 실데이터(红包): 42행은 "행운 봉투", 1행은 "빨간 봉투". "봉투"의 점수가 근소하게 높아도 "행운 봉투"를 같이 보여 준다 —
        // 검수자가 둘 중 고른다. 둘째 낱말 관계에서는 긴 쪽을 지우지 않는다.
        List<Row> rows = List.of(
                r("领取红包", "행운 봉투 받기"), r("发送红包", "행운 봉투 보내기"), r("红包已领完", "행운 봉투가 소진되었습니다"),
                r("红包下注", "빨간 봉투 베팅"),
                r("领取奖励", "보상 받기"), r("幸运抽奖", "행운 뽑기"), r("幸运值", "행운 수치"));
        Result res = GlossarySuggester.suggest(rows, "红包", "X");
        assertThat(names(res)).contains("행운 봉투", "봉투");
        assertThat(names(res)).doesNotContain("행운");
    }

    /** 한 꼴의 한국어 5행 + 晋升→승급 5행. 후보 계산이 예외 없이 승급을 1순위로 내고, 모든 후보가 예시 행에 실제로 들어 있어야 한다. */
    private static void assertStemSurvives(String koShape) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(r("升星奖励" + i, koShape + " " + i));
        for (int i = 0; i < 5; i++) rows.add(r("晋升" + i, "승급 " + i));
        for (int i = 0; i < 5; i++) rows.add(r("奖励" + i, "보상 " + i));          // 이웃 낱말이 다른 원문에도 나와야 설명 제거가 된다
        for (int i = 0; i < 5; i++) rows.add(r("宝箱" + i, "보물상자 " + i));
        Result res = GlossarySuggester.suggest(rows, "升星", "성급");
        assertThat(names(res)).as(koShape).startsWith("승급");
        for (Candidate c : res.candidates()) assertThat(c.example().before()).as(koShape).contains(c.oldKo());
    }

    @Test
    void punctuationAndMarkupBetweenWordsDoNotFabricateTwoTokenCandidates() {
        // 리뷰 Critical 1: "승급(1성) 보상"·"<color=#ff0>승급</color> 보물상자"에서 한글 구간을 이어 붙인 "승급성 보상" 같은
        // 실제로 없는 두 토큰 후보가 만들어지면 b≈0으로 1순위가 되고 예시 행을 못 찾아 예외가 났다.
        assertStemSurvives("승급! 보상");
        assertStemSurvives("승급(1성) 보상");
        assertStemSurvives("<color=#ff0>승급</color> 보물상자");
    }

    @Test
    void explainAwayWalksPastCrowdedNeighborCandidates() {
        // 리뷰 Important 2: 이웃 낱말 하나가 접두사·쌍 후보 여섯 개를 만들어 상위 5개를 채우면, 전부 설명 제거로 빠지고
        // 6순위 밑의 진짜 후보("승급")가 검토되지 못해 빈 결과가 났다.
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) rows.add(r("升星宝箱" + i, "승급 보물상자 " + i));
        for (int i = 0; i < 40; i++) rows.add(r("晋升" + i, "승급 " + i));
        for (int i = 0; i < 20; i++) rows.add(r("宝箱" + i, "보물상자 " + i));
        Result res = GlossarySuggester.suggest(rows, "升星", "성급");
        assertThat(names(res)).contains("승급");
    }
}
