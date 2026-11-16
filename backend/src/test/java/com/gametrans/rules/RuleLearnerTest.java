package com.gametrans.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.gametrans.rules.RuleLearner.Edit;
import com.gametrans.rules.RuleLearner.Learned;
import com.gametrans.rules.RuleLearner.Row;
import com.gametrans.rules.RuleLearner.RuleKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuleLearnerTest {
    private long seq = 1;
    private final List<Edit> edits = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();

    /** 손 수정 하나 + 그 행(지금 글 = after). */
    private long edit(String src, String before, String after, Character kind) {
        long id = seq++;
        edits.add(new Edit(id, src, before, after, Instant.ofEpochSecond(id)));
        rows.add(new Row(id, 1L, src, after, kind));
        return id;
    }
    private long row(String src, String ko, Character kind) { long id = seq++; rows.add(new Row(id, 1L, src, ko, kind)); return id; }

    private List<Learned> learn(Map<Character, String> policies, Map<RuleKey, Set<Long>> excl) {
        return new RuleLearner().learn(edits, rows, policies, excl, true);
    }
    private List<Learned> learn() { return learn(Map.of(), Map.of()); }
    private Learned style(List<Learned> ls, char kind) {
        return ls.stream().filter(l -> l.key().type().equals("STYLE") && l.key().textKind() == kind).findFirst().orElseThrow();
    }

    @Test
    void learnsNoWordRules() {
        edit("精英装备", "엘리트 장비", "에픽 장비", null);
        edit("精英装备", "엘리트 장비", "에픽 장비", null);
        assertThat(learn()).noneMatch(l -> l.key().type().equals("WORD"));
    }

    @Test
    void findsKindsWithoutEdits() {
        long effect = row("攻击提升", "공격력이 증가합니다.", 'E');
        row("你好呀", "반갑습니다.", 'D');
        row("攻击提升", "방어력이 증가합니다.", null);
        row("继续挑战", "계속하시겠습니까?", 'Q');

        List<Learned> ls = learn();
        assertThat(ls.stream().filter(l -> l.key().type().equals("STYLE"))).hasSize(1);
        Learned e = style(ls, 'E');
        assertThat(e.candidates()).containsExactly(effect);
        assertThat(e.ratio()).isEqualTo(0.0);
        assertThat(e.converted()).isZero();
        assertThat(e.kept()).isZero();
    }

    @Test
    void periodRuleCoversEveryKindButExclusions() {
        long dialog = row("你好呀", "반갑습니다.", 'D');
        long unjudged = row("攻击提升", "공격력 증가.<br>방어력 증가", null);
        long excluded = row("攻击提升", "공격력 증가.", 'E');
        row("很久以前", "옛날 옛적에...", 'T');
        row("很久以前", "마침표 없음", 'T');

        Learned p = learn(Map.of(), Map.of(RuleLearner.PERIOD, Set.of(excluded))).stream()
                .filter(l -> l.key().equals(RuleLearner.PERIOD)).findFirst().orElseThrow();
        assertThat(p.candidates()).containsExactlyInAnyOrder(dialog, unjudged);
        assertThat(p.verdict()).isEqualTo("RULE");
    }

    @Test
    void noPeriodRuleWithoutCandidates() {
        row("很久以前", "마침표 없음", 'T');
        assertThat(learn()).noneMatch(l -> l.key().equals(RuleLearner.PERIOD));
    }

    @Test
    void styleVerdicts() {
        for (int i = 0; i < 8; i++) edit("攻击提升", "공격력이 증가합니다.", "공격력 증가.", 'E');
        for (int i = 0; i < 2; i++) edit("造成伤害", "피해를 줍니다.", "적에게 피해를 줍니다.", 'E');
        edit("很久以前", "옛날 옛적에 왕국이 있었습니다.", "옛날 왕국이 있었음.", 'T');
        for (int i = 0; i < 9; i++) edit("很久以前", "옛날 옛적에 왕국이 있었습니다.", "아주 먼 옛날 왕국이 있었습니다.", 'T');
        for (int i = 0; i < 5; i++) edit("无法退出", "탈퇴할 수 없습니다.", "탈퇴 불가능.", 'S');
        for (int i = 0; i < 5; i++) edit("无法退出", "탈퇴할 수 없습니다.", "길드장은 탈퇴할 수 없습니다.", 'S');
        for (int i = 0; i < 3; i++) edit("达成条件", "달성합니다.", "달성함.", 'Q');
        for (int i = 0; i < 10; i++) edit("你好呀", "반갑습니다.", "반가움.", 'D');
        for (int i = 0; i < 10; i++) edit("亲爱的玩家", "보상을 지급했습니다.", "보상 지급함.", 'N');
        long left = row("攻击提升", "방어력이 증가합니다.", 'E');
        long question = row("继续挑战", "계속 도전하시겠습니까?", 'E');

        List<Learned> ls = learn();
        assertThat(style(ls, 'E').verdict()).isEqualTo("RULE");
        assertThat(style(ls, 'E').converted()).isEqualTo(8);
        assertThat(style(ls, 'E').candidates()).contains(left).doesNotContain(question);
        assertThat(style(ls, 'T').verdict()).isEqualTo("KEEP");
        assertThat(style(ls, 'S').verdict()).isEqualTo("DECIDE");
        assertThat(style(ls, 'Q').verdict()).isEqualTo("INSUFFICIENT");
        assertThat(ls).noneMatch(l -> Character.valueOf('D').equals(l.key().textKind()) || Character.valueOf('N').equals(l.key().textKind()));   // 대사·공지는 늘 뺀다
        assertThat(style(learn(Map.of('S', "KEEP"), Map.of()), 'S').verdict()).isEqualTo("KEEP");
    }
}
