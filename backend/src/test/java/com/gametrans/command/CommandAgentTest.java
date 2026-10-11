package com.gametrans.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gametrans.FakeLlmClient;
import com.gametrans.josa.JosaHint;
import com.gametrans.llm.LlmClient.AssistantTurn;
import com.gametrans.llm.LlmClient.ToolCall;
import java.util.List;
import org.junit.jupiter.api.Test;

/** CommandAgent(2026-10-08): GPT가 도구로 시험·확인한 뒤 finish로 작업 목록을 낸다. */
class CommandAgentTest {
    final FakeLlmClient llm = new FakeLlmClient();
    final PipelineTest.FakeSidecar sidecar = new PipelineTest.FakeSidecar();
    final CommandAgent agent = new CommandAgent(llm, new ObjectMapper());
    final List<SheetRow> rows = List.of(PipelineTest.row(1, "九尾狐", "구미호이 왔다"), PipelineTest.row(2, "x", "늑대: 유화린"),
            PipelineTest.row(3, "{0}点", "점"));

    static ToolCall call(String id, String name, String args) { return new ToolCall(id, name, args); }

    @Test
    void triesOpsSeesJosaWarningAddsJosaAndFinishes() {
        sidecar.josa.put("여우이 왔다", List.of(new JosaHint(0, "여우이", "여우가", "HANGUL", null)));
        String replace = "{\"kind\":\"REPLACE\",\"steps\":[{\"find\":\"구미호\",\"replace\":\"여우\",\"regex\":false}]}";
        llm.toolTurns.add(new AssistantTurn(null, List.of(call("c1", "try_ops", "{\"ops\":[" + replace + "]}"))));
        llm.toolTurns.add(new AssistantTurn(null, List.of(call("c2", "finish",
                "{\"summary\":\"구미호를 여우로 바꾸고 조사를 맞춥니다.\",\"ops\":[" + replace + ",{\"kind\":\"JOSA\"}]}"))));
        CommandAgent.Outcome out = agent.run("구미호를 여우로 바꾸고 조사도 맞춰줘", rows, sidecar);
        assertThat(out.summary()).isEqualTo("구미호를 여우로 바꾸고 조사를 맞춥니다.");
        assertThat(out.ops()).extracting(OpSpec::kind).containsExactly(OpSpec.REPLACE, OpSpec.JOSA);
        assertThat(out.tagMismatch()).isFalse();
        String tried = out.trail().get(0).result();
        assertThat(tried).contains("\"changed\":1").contains("여우이 → 여우가");
        assertThat((String) llm.toolMessages.get(0).get(1).get("content")).contains("구미호를 여우로").contains("구미호이 왔다");
    }

    @Test
    void findRowsAndTagMismatchAnswerFromTheSheet() {
        llm.toolTurns.add(new AssistantTurn(null, List.of(call("c1", "find_rows", "{\"contains\":\"유화린\"}"))));
        llm.toolTurns.add(new AssistantTurn(null, List.of(call("c2", "find_tag_mismatch", "{}"))));
        llm.toolTurns.add(new AssistantTurn(null, List.of(call("c3", "finish", "{\"summary\":\"태그 짝이 안 맞는 행입니다.\",\"tag_mismatch\":true}"))));
        CommandAgent.Outcome out = agent.run("태그 깨진 행 찾아줘", rows, sidecar);
        assertThat(out.tagMismatch()).isTrue();
        assertThat(out.ops()).isNull();
        assertThat(out.trail().get(0).result()).contains("\"count\":1").contains("늑대: 유화린");
        assertThat(out.trail().get(1).result()).contains("\"count\":1").contains("{0}点");
    }

    @Test
    void findRowsShowsAtMostEightRowsButCountsAll() {
        List<SheetRow> many = new java.util.ArrayList<>();
        for (int i = 1; i <= 12; i++) many.add(PipelineTest.row(i, "x", "여우 " + i));
        llm.toolTurns.add(new AssistantTurn(null, List.of(call("c1", "find_rows", "{\"contains\":\"여우\",\"limit\":20}"))));
        llm.toolTurns.add(new AssistantTurn(null, List.of(call("c2", "finish", "{\"summary\":\"s\",\"tag_mismatch\":true}"))));
        String res = agent.run("x", many, sidecar).trail().get(0).result();
        assertThat(res).contains("\"count\":12");
        assertThat(res.split("\"ko\"", -1).length - 1).isEqualTo(8);
    }

    @Test
    void badOpsGoBackAsErrorsAndInvalidFinishIsRejected() {
        llm.toolTurns.add(new AssistantTurn(null, List.of(call("c1", "try_ops",
                "{\"ops\":[{\"kind\":\"REPLACE\",\"steps\":[{\"find\":\"(\",\"replace\":\"\",\"regex\":true}]}]}"))));
        llm.toolTurns.add(new AssistantTurn(null, List.of(call("c2", "finish", "{\"summary\":\"s\",\"ops\":[{\"kind\":\"NOPE\"}]}"))));
        llm.toolTurns.add(new AssistantTurn(null, List.of(call("c3", "finish", "{\"summary\":\"조사를 맞춥니다.\",\"ops\":[{\"kind\":\"JOSA\"}]}"))));
        CommandAgent.Outcome out = agent.run("x", rows, sidecar);
        assertThat(out.summary()).isEqualTo("조사를 맞춥니다.");
        assertThat(out.ops()).extracting(OpSpec::kind).containsExactly(OpSpec.JOSA);
        assertThat((String) llm.toolMessages.get(1).get(3).get("content")).startsWith("{\"error\":\"정규식이 올바르지 않습니다");
        assertThat((String) llm.toolMessages.get(2).get(5).get("content")).isEqualTo("{\"error\":\"모르는 작업입니다: NOPE\"}");
        assertThat(out.trail()).extracting(t -> t.tool()).containsExactly("try_ops", "finish");
    }

    @Test
    void finishWithoutOpsIsRejectedAndRunningOutOfCallsFails() {
        for (int i = 0; i < CommandAgent.MAX_CALLS; i++)
            llm.toolTurns.add(new AssistantTurn(null, List.of(call("c" + i, "finish", "{\"summary\":\"s\"}"))));
        assertThatThrownBy(() -> agent.run("x", rows, sidecar)).isInstanceOf(IllegalArgumentException.class).hasMessage(com.gametrans.llm.ToolLoop.UNFINISHED);
        assertThat((String) llm.toolMessages.get(1).get(3).get("content")).isEqualTo("{\"error\":\"작업이 없습니다.\"}");
    }

    @Test
    void plainTextAnswerIsTheRefusal() {
        llm.toolTurns.add(new AssistantTurn("뜻을 보고 고쳐야 해서 찾아 바꾸기로는 할 수 없습니다.", List.of()));
        assertThatThrownBy(() -> agent.run("어색한 문장 다듬어줘", rows, sidecar))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("뜻을 보고 고쳐야 해서 찾아 바꾸기로는 할 수 없습니다.");
    }

    @Test
    void cannotIsTheRefusal() {
        llm.toolTurns.add(new AssistantTurn(null, List.of(call("c1", "cannot", "{\"reason\":\"뜻을 보고 고쳐야 해서 찾아 바꾸기로는 할 수 없습니다.\"}"))));
        assertThatThrownBy(() -> agent.run("어색한 문장 다듬어줘", rows, sidecar))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("뜻을 보고 고쳐야 해서 찾아 바꾸기로는 할 수 없습니다.");
        assertThat(llm.toolSpecsSeen.get(0)).extracting(s -> s.name())
                .containsExactly("find_rows", "try_ops", "check_josa", "find_tag_mismatch", "finish", "cannot");
    }

    @Test
    void cannotAfterAWorkingTryStaysARefusal() {
        // 일부러 그만둔 것은 미리 보기로 바꾸지 않는다 — 시험이 행을 바꿨어도 GPT가 틀렸다고 본 규칙일 수 있다
        String replace = "{\"kind\":\"REPLACE\",\"steps\":[{\"find\":\"구미호\",\"replace\":\"여우\",\"regex\":false}]}";
        llm.toolTurns.add(new AssistantTurn(null, List.of(call("c1", "try_ops", "{\"ops\":[" + replace + "]}"))));
        llm.toolTurns.add(new AssistantTurn(null, List.of(call("c2", "cannot", "{\"reason\":\"문맥을 봐야 합니다.\"}"))));
        assertThatThrownBy(() -> agent.run("구미호 자연스럽게", rows, sidecar))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("문맥을 봐야 합니다.");
    }

    @Test
    void askingForConfirmationAfterAWorkingTryUsesTheTriedOps() {
        // 프로드 2026-10-08: GPT가 try_ops로 84행을 확인하고 finish 대신 "원하시면 적용하겠습니다"로 끝냈다 — 미리 보기가 곧 확인이다
        String replace = "{\"kind\":\"REPLACE\",\"steps\":[{\"find\":\"구미호\",\"replace\":\"여우\",\"regex\":false}]}";
        llm.toolTurns.add(new AssistantTurn(null, List.of(new ToolCall("c1", "try_ops", "{\"ops\":[" + replace + "]}"))));
        llm.toolTurns.add(new AssistantTurn("구미호를 여우로 바꿉니다. 1개 행이 변경됩니다. 원하시면 적용하겠습니다.", List.of()));
        CommandAgent.Outcome out = agent.run("구미호를 여우로", rows, sidecar);
        assertThat(out.ops()).extracting(OpSpec::kind).containsExactly(OpSpec.REPLACE);
        assertThat(out.ops().get(0).steps().get(0).replace()).isEqualTo("여우");
        assertThat(out.summary()).isEqualTo("구미호를 여우로 바꿉니다. 1개 행이 변경됩니다. 원하시면 적용하겠습니다.");
        assertThat(out.tagMismatch()).isFalse();
    }

    @Test
    void aTryThatChangedNothingStaysARefusal() {
        String none = "{\"kind\":\"REPLACE\",\"steps\":[{\"find\":\"없는말\",\"replace\":\"x\",\"regex\":false}]}";
        llm.toolTurns.add(new AssistantTurn(null, List.of(new ToolCall("c1", "try_ops", "{\"ops\":[" + none + "]}"))));
        llm.toolTurns.add(new AssistantTurn("바뀌는 행이 없어 할 수 없습니다.", List.of()));
        assertThatThrownBy(() -> agent.run("없는말 바꿔줘", rows, sidecar))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("바뀌는 행이 없어 할 수 없습니다.");
    }

    @Test
    void samplesPreferRowsWithCommandWordsOrSymbols() {
        assertThat(CommandAgent.samples(rows, "구미호 바꿔줘").get(0)).isEqualTo("구미호이 왔다");
        assertThat(CommandAgent.samples(rows, ": 를 · 로").get(0)).isEqualTo("늑대: 유화린");
        assertThat(CommandAgent.samples(List.of(), "x")).isEmpty();
    }
}
