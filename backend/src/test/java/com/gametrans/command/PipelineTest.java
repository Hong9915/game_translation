package com.gametrans.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gametrans.josa.JosaHint;
import com.gametrans.llm.LlmClient.CommandFilter;
import com.gametrans.llm.LlmClient.CommandStep;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 명령으로 고치기 작업 목록(설계 2026-10-08 §1): Op를 차례로 적용하고 경고를 붙인다. */
class PipelineTest {
    /** 문장 → 제안, 문장 → 태그 띄운 꼴. 받은 문장은 seen에 남는다. */
    static class FakeSidecar implements Pipeline.Sidecar {
        final Map<String, List<JosaHint>> josa = new HashMap<>();
        final Map<String, String> spaced = new HashMap<>();
        final List<String> josaSeen = new ArrayList<>();
        boolean down;
        @Override public List<String> tagSpace(List<String> texts) {
            if (down) throw new IllegalStateException("띄어쓰기·조사 검사기에 연결하지 못했습니다. 잠시 뒤 다시 해 주세요.");
            return texts.stream().map(t -> spaced.getOrDefault(t, t)).toList();
        }
        @Override public List<List<JosaHint>> josa(List<String> texts) {
            if (down) throw new IllegalStateException("띄어쓰기·조사 검사기에 연결하지 못했습니다. 잠시 뒤 다시 해 주세요.");
            josaSeen.addAll(texts);
            return texts.stream().map(t -> josa.getOrDefault(t, List.of())).toList();
        }
    }

    static SheetRow row(long id, String src, String ko) { return new SheetRow(id, (int) id, src, ko, false, true, null, false); }
    static SheetRow confirmed(long id, String src, String ko) { return new SheetRow(id, (int) id, src, ko, true, true, null, false); }
    static OpSpec replace(String find, String to) { return OpSpec.replace(null, List.of(new CommandStep(find, to, false))); }

    final FakeSidecar sidecar = new FakeSidecar();

    @Test
    void replaceThenJosaFixesTheParticleTheReplaceBroke() {
        sidecar.josa.put("여우이 왔다", List.of(new JosaHint(0, "여우이", "여우가", "HANGUL", null)));
        Pipeline p = Pipeline.compile(List.of(replace("구미호", "여우"), OpSpec.of(OpSpec.JOSA)));
        List<Pipeline.Change> out = p.run(List.of(row(1, "九尾狐来了", "구미호이 왔다"), row(2, "x", "변화 없음")), null, sidecar);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).after()).isEqualTo("여우가 왔다");
        assertThat(out.get(0).by()).containsExactly(OpSpec.REPLACE, OpSpec.JOSA);
        assertThat(out.get(0).warnings()).isEmpty();
    }

    @Test
    void josaUsesStoredHintsOnlyForUnchangedCheckedRows() {
        String stored = "[{\"at\":0,\"from\":\"생명를\",\"to\":\"생명을\",\"kind\":\"HANGUL\"}]";
        SheetRow checked = new SheetRow(1L, 1, "s", "생명를 얻다", false, true, stored, false);
        SheetRow ignored = new SheetRow(2L, 2, "s", "생명를 잃다", false, true, stored, true);
        SheetRow pending = new SheetRow(3L, 3, "s", "면역를 얻다", false, false, null, false);
        sidecar.josa.put("면역를 얻다", List.of(new JosaHint(0, "면역를", "면역을", "HANGUL", null)));
        List<Pipeline.Change> out = Pipeline.compile(List.of(OpSpec.of(OpSpec.JOSA))).run(List.of(checked, ignored, pending), null, sidecar);
        assertThat(out).extracting(Pipeline.Change::after).containsExactly("생명을 얻다", "면역을 얻다");
        assertThat(sidecar.josaSeen).containsExactly("면역를 얻다");      // 검사된 행은 사이드카로 보내지 않는다
    }

    @Test
    void josaSkipsTagKind() {
        sidecar.josa.put("A<color=red>3</color>로", List.of(new JosaHint(21, "로", "으로", "TAG", null)));
        SheetRow r = new SheetRow(1L, 1, "s", "A<color=red>3</color>로", false, false, null, false);
        assertThat(Pipeline.compile(List.of(OpSpec.of(OpSpec.JOSA))).run(List.of(r), null, sidecar)).isEmpty();
    }

    @Test
    void tagSpacingKeepsOnlySpacingChanges() {
        sidecar.spaced.put("적에게<b>함정</b>피해", "적에게 <b>함정</b> 피해");
        sidecar.spaced.put("<b>A</b>B", "<b>A</b> C");                  // 글자가 바뀐 답은 버린다
        List<Pipeline.Change> out = Pipeline.compile(List.of(OpSpec.of(OpSpec.TAG_SPACING)))
                .run(List.of(row(1, "s", "적에게<b>함정</b>피해"), row(2, "s", "<b>A</b>B"), row(3, "s", "태그 없음")), null, sidecar);
        assertThat(out).extracting(Pipeline.Change::after).containsExactly("적에게 <b>함정</b> 피해");
    }

    @Test
    void unifyPrefersConfirmedAndSkipsTiesAndConflicts() {
        List<SheetRow> rows = List.of(
                confirmed(1, "攻击", "공격력"), row(2, "攻击", "공격"), row(3, "攻击", "공격"),           // 확정 우선
                row(4, "生命", "생명"), row(5, "生命", "생명"), row(6, "生命", "체력"),                   // 다수
                row(7, "暴击", "치명"), row(8, "暴击", "크리"),                                          // 동점 → 건너뜀
                confirmed(9, "防御", "방어"), confirmed(10, "防御", "방어력"), row(11, "防御", "방어도"));  // 확정끼리 다름 → 건너뜀
        List<Pipeline.Change> out = Pipeline.compile(List.of(OpSpec.of(OpSpec.UNIFY_SAME_SOURCE))).run(rows, null, sidecar);
        assertThat(out).extracting(c -> c.row().id() + ":" + c.after()).containsExactly("2:공격력", "3:공격력", "6:생명");
    }

    @Test
    void confirmedRowsAreNeverChangedAndOnlyLimitsTargets() {
        Pipeline p = Pipeline.compile(List.of(replace("-", "–")));
        List<SheetRow> rows = List.of(confirmed(1, "s", "A-B"), row(2, "s", "C-D"), row(3, "s", "E-F"));
        assertThat(p.run(rows, null, sidecar)).extracting(c -> c.row().id()).containsExactly(2L, 3L);
        assertThat(p.run(rows, Set.of(3L), sidecar)).extracting(c -> c.row().id()).containsExactly(3L);
    }

    @Test
    void warnsAboutNewJosaProblemsWhenNoJosaOp() {
        sidecar.josa.put("여우이 왔다", List.of(new JosaHint(0, "여우이", "여우가", "HANGUL", null)));
        List<Pipeline.Change> out = Pipeline.compile(List.of(replace("곰", "여우")))
                .run(List.of(row(1, "熊", "곰이 왔다"), unchecked(2, "곰이 왔다")), null, sidecar);
        assertThat(out).extracting(Pipeline.Change::after).containsExactly("여우이 왔다", "여우이 왔다");
        assertThat(out).allSatisfy(c -> assertThat(c.warnings()).containsExactly(new Pipeline.Warning("JOSA", "여우이 → 여우가")));
    }

    @Test
    void doesNotWarnAboutJosaProblemsThatWereAlreadyThere() {
        JosaHint old = new JosaHint(0, "구미호이", "구미호가", "HANGUL", null);
        sidecar.josa.put("구미호이 왔다", List.of(old));
        sidecar.josa.put("구미호이 갔다", List.of(old));
        String stored = "[{\"at\":0,\"from\":\"구미호이\",\"to\":\"구미호가\",\"kind\":\"HANGUL\"}]";
        SheetRow checked = new SheetRow(2L, 2, "s", "구미호이 왔다", false, true, stored, false);
        List<Pipeline.Change> out = Pipeline.compile(List.of(replace("왔다", "갔다")))
                .run(List.of(unchecked(1, "구미호이 왔다"), checked), null, sidecar);
        assertThat(out).extracting(Pipeline.Change::after).containsExactly("구미호이 갔다", "구미호이 갔다");
        assertThat(out).allSatisfy(c -> assertThat(c.warnings()).isEmpty());
        // 검사 안 된 행은 원래 글도 같은 사이드카 호출로 보고, 검사된 행은 저장된 제안을 쓴다
        assertThat(sidecar.josaSeen).containsExactlyInAnyOrder("구미호이 왔다", "구미호이 갔다", "구미호이 갔다");
    }

    @Test
    void ignoredRowsGetNoJosaWarnings() {
        sidecar.josa.put("여우이 왔다", List.of(new JosaHint(0, "여우이", "여우가", "HANGUL", null)));
        SheetRow ignored = new SheetRow(1L, 1, "s", "곰이 왔다", false, true, null, true);
        List<Pipeline.Change> out = Pipeline.compile(List.of(replace("곰", "여우"))).run(List.of(ignored), null, sidecar);
        assertThat(out).singleElement().satisfies(c -> assertThat(c.warnings()).isEmpty());
    }

    @Test
    void runWithoutWarningsSkipsTheSidecar() {
        sidecar.josa.put("여우이 왔다", List.of(new JosaHint(0, "여우이", "여우가", "HANGUL", null)));
        List<Pipeline.Change> out = Pipeline.compile(List.of(replace("곰", "여우"))).run(List.of(unchecked(1, "곰이 왔다")), null, sidecar, false);
        assertThat(out).singleElement().satisfies(c -> assertThat(c.warnings()).isEmpty());
        assertThat(sidecar.josaSeen).isEmpty();
    }

    @Test
    void warnsWhenTheResultBreaksPlaceholders() {
        // 찾아 바꾸기는 {…}·태그 안을 못 건드리니, 짝을 깨는 건 같은 원문 통일이 짝 안 맞는 확정 번역을 퍼뜨릴 때다
        List<Pipeline.Change> out = Pipeline.compile(List.of(OpSpec.of(OpSpec.UNIFY_SAME_SOURCE)))
                .run(List.of(confirmed(1, "{0}点", "점"), row(2, "{0}点", "{0}점")), null, sidecar);
        assertThat(out).singleElement().satisfies(c -> {
            assertThat(c.after()).isEqualTo("점");
            assertThat(c.warnings()).containsExactly(new Pipeline.Warning("TAG", Pipeline.TAG_BROKEN));
        });
    }

    @Test
    void replaceCarriesColorTagsThroughWithoutTagWarning() {
        // 숫자만 다른 22개 문장 같은 꼴: 태그를 그룹으로 옮기면 바뀌고 태그 짝 경고도 없다
        OpSpec op = OpSpec.replace(null, List.of(new CommandStep("피해 계수가(<color=[^>]*>)([0-9.]+%)(</color>)로 증가했습니다", "피해 계수 $1$2$3 증가", true)));
        List<Pipeline.Change> out = Pipeline.compile(List.of(op))
                .run(List.of(row(1, "伤害系数提升至<color=#ff0000>75%</color>", "피해 계수가<color=#ff0000>75%</color>로 증가했습니다")), null, sidecar);
        assertThat(out).singleElement().satisfies(c -> {
            assertThat(c.after()).isEqualTo("피해 계수 <color=#ff0000>75%</color> 증가");
            assertThat(c.by()).containsExactly(OpSpec.REPLACE);
            assertThat(c.warnings()).isEmpty();
        });
    }

    @Test
    void replaceStillWorksWhenSidecarIsDown() {
        sidecar.down = true;
        List<Pipeline.Change> out = Pipeline.compile(List.of(replace("구미호", "여우"))).run(List.of(row(1, "s", "구미호")), null, sidecar);
        assertThat(out).extracting(Pipeline.Change::after).containsExactly("여우");
        assertThat(out.get(0).warnings()).isEmpty();
        assertThatThrownBy(() -> Pipeline.compile(List.of(OpSpec.of(OpSpec.JOSA))).run(List.of(new SheetRow(1L, 1, "s", "x", false, false, null, false)), null, sidecar))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBadPipelines() {
        assertThatThrownBy(() -> Pipeline.compile(List.of())).hasMessage("작업이 없습니다.");
        assertThatThrownBy(() -> Pipeline.compile(List.of(OpSpec.of(OpSpec.JOSA), OpSpec.of(OpSpec.JOSA), OpSpec.of(OpSpec.JOSA),
                OpSpec.of(OpSpec.JOSA), OpSpec.of(OpSpec.JOSA), OpSpec.of(OpSpec.JOSA), OpSpec.of(OpSpec.JOSA)))).hasMessage("작업이 너무 많습니다.");
        assertThatThrownBy(() -> Pipeline.compile(List.of(OpSpec.of("DELETE_ALL")))).hasMessage("모르는 작업입니다: DELETE_ALL");
        assertThatThrownBy(() -> Pipeline.compile(List.of(OpSpec.of(null)))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("모르는 작업입니다: null");
        assertThatThrownBy(() -> Pipeline.compile(List.of(OpSpec.of(OpSpec.JOSA), OpSpec.of(OpSpec.UNIFY_SAME_SOURCE))))
                .hasMessage("같은 원문 통일은 맨 앞에만 둘 수 있습니다.");
        assertThatThrownBy(() -> Pipeline.compile(List.of(OpSpec.replace(new CommandFilter("ko", "x"), List.of()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static SheetRow unchecked(long id, String ko) { return new SheetRow(id, (int) id, "s", ko, false, false, null, false); }

    @Test
    void josaAfterReplaceFixesOnlyRowsTheReplaceChanged() {
        sidecar.josa.put("여우이 왔다", List.of(new JosaHint(0, "여우이", "여우가", "HANGUL", null)));
        sidecar.josa.put("보호막를 얻다", List.of(new JosaHint(0, "보호막를", "보호막을", "HANGUL", null)));
        Pipeline p = Pipeline.compile(List.of(replace("구미호", "여우"), OpSpec.of(OpSpec.JOSA)));
        List<Pipeline.Change> out = p.run(List.of(unchecked(1, "구미호이 왔다"), unchecked(2, "보호막를 얻다")), null, sidecar);
        assertThat(out).extracting(c -> c.row().id()).containsExactly(1L);
        assertThat(out.get(0).after()).isEqualTo("여우가 왔다");
        assertThat(sidecar.josaSeen).doesNotContain("보호막를 얻다");
    }

    @Test
    void josaAloneCoversTheWholeSheet() {
        sidecar.josa.put("보호막를 얻다", List.of(new JosaHint(0, "보호막를", "보호막을", "HANGUL", null)));
        List<Pipeline.Change> out = Pipeline.compile(List.of(OpSpec.of(OpSpec.JOSA))).run(List.of(unchecked(2, "보호막를 얻다")), null, sidecar);
        assertThat(out).extracting(Pipeline.Change::after).containsExactly("보호막을 얻다");
    }

    @Test
    void tagSpacingAfterReplaceOnlyTouchesChangedRows() {
        sidecar.spaced.put("여우<b>A</b>B", "여우 <b>A</b> B");
        sidecar.spaced.put("적에게<b>함정</b>피해", "적에게 <b>함정</b> 피해");
        Pipeline p = Pipeline.compile(List.of(replace("구미호", "여우"), OpSpec.of(OpSpec.TAG_SPACING)));
        List<Pipeline.Change> out = p.run(List.of(row(1, "s", "구미호<b>A</b>B"), row(2, "s", "적에게<b>함정</b>피해")), null, sidecar);
        assertThat(out).extracting(c -> c.row().id()).containsExactly(1L);
        assertThat(out.get(0).after()).isEqualTo("여우 <b>A</b> B");
    }
}
