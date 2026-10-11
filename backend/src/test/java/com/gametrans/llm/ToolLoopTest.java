package com.gametrans.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gametrans.FakeLlmClient;
import com.gametrans.llm.LlmClient.AssistantTurn;
import com.gametrans.llm.LlmClient.ToolCall;
import com.gametrans.llm.LlmClient.ToolSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ToolLoop(2026-10-08): 도구 호출 → 결과를 role:tool로 돌려주기 → finish에서 멈춤. */
class ToolLoopTest {
    final FakeLlmClient llm = new FakeLlmClient();
    final ObjectMapper mapper = new ObjectMapper();

    static ToolLoop.Tool tool(String name, java.util.function.Function<JsonNode, String> f) {
        return new ToolLoop.Tool() {
            @Override public ToolSpec spec() { return new ToolSpec(name, name + " 도구", Map.of("type", "object")); }
            @Override public String call(JsonNode args) { return f.apply(args); }
        };
    }

    final List<ToolLoop.Tool> tools = List.of(
            tool("echo", a -> "{\"got\":\"" + a.path("x").asText() + "\"}"),
            tool("boom", a -> { throw new IllegalArgumentException("정규식이 올바르지 않습니다"); }),
            tool("finish", a -> "ok"),
            tool("cannot", a -> "ok"));

    @Test
    void callsToolsFeedsResultsBackAndStopsAtFinish() {
        llm.toolTurns.add(new AssistantTurn(null, List.of(new ToolCall("c1", "echo", "{\"x\":\"hi\"}"))));
        llm.toolTurns.add(new AssistantTurn("다 했다", List.of(new ToolCall("c2", "finish", "{\"summary\":\"끝\"}"))));
        ToolLoop.Result r = ToolLoop.run(llm, mapper, "CHAT_COMMAND", "sys", "user", tools, "finish", 6);
        assertThat(r.finish().path("summary").asText()).isEqualTo("끝");
        assertThat(r.trail()).extracting(ToolLoop.Trace::tool).containsExactly("echo");
        assertThat(r.trail().get(0).result()).isEqualTo("{\"got\":\"hi\"}");
        List<Map<String, Object>> second = llm.toolMessages.get(1);
        assertThat(second).extracting(m -> m.get("role")).containsExactly("system", "user", "assistant", "tool");
        assertThat(second.get(3)).containsEntry("tool_call_id", "c1").containsEntry("content", "{\"got\":\"hi\"}");
        assertThat((List<?>) second.get(2).get("tool_calls")).hasSize(1);
    }

    @Test
    void unknownToolAndBadJsonGoBackAsErrors() {
        llm.toolTurns.add(new AssistantTurn(null, List.of(new ToolCall("c1", "nope", "{}"))));
        llm.toolTurns.add(new AssistantTurn(null, List.of(new ToolCall("c2", "echo", "{not json"))));
        llm.toolTurns.add(new AssistantTurn(null, List.of(new ToolCall("c3", "boom", "{}"))));
        llm.toolTurns.add(new AssistantTurn(null, List.of(new ToolCall("c4", "finish", "{}"))));
        ToolLoop.Result r = ToolLoop.run(llm, mapper, "CHAT_COMMAND", "sys", "user", tools, "finish", 6);
        assertThat(r.trail()).extracting(ToolLoop.Trace::result).containsExactly(
                "{\"error\":\"모르는 도구입니다: nope\"}",
                "{\"error\":\"인자가 JSON이 아닙니다.\"}",
                "{\"error\":\"정규식이 올바르지 않습니다\"}");
    }

    @Test
    void plainTextEndsWithoutFinish() {
        llm.toolTurns.add(new AssistantTurn("뜻을 봐야 해서 못 합니다.", List.of()));
        ToolLoop.Result r = ToolLoop.run(llm, mapper, "CHAT_COMMAND", "sys", "user", tools, "finish", 6);
        assertThat(r.finish()).isNull();
        assertThat(r.text()).isEqualTo("뜻을 봐야 해서 못 합니다.");
    }

    @Test
    void giveUpToolEndsWithItsReason() {
        // tool_choice=required라 모델은 글만 낼 수 없다 — 할 수 없다는 말도 도구(cannot)로 한다
        llm.toolTurns.add(new AssistantTurn(null, List.of(new ToolCall("c1", "echo", "{\"x\":\"a\"}"))));
        llm.toolTurns.add(new AssistantTurn(null, List.of(new ToolCall("c2", "cannot", "{\"reason\":\"뜻을 봐야 해서 못 합니다.\"}"))));
        ToolLoop.Result r = ToolLoop.run(llm, mapper, "CHAT_COMMAND", "sys", "user", tools, "finish", "cannot", 6, a -> null);
        assertThat(r.finish()).isNull();
        assertThat(r.gaveUp()).isTrue();
        assertThat(r.text()).isEqualTo("뜻을 봐야 해서 못 합니다.");
        assertThat(r.trail()).extracting(ToolLoop.Trace::tool).containsExactly("echo");
    }

    @Test
    void giveUpWithoutReasonGoesBackAsError() {
        llm.toolTurns.add(new AssistantTurn(null, List.of(new ToolCall("c1", "cannot", "{}"))));
        llm.toolTurns.add(new AssistantTurn(null, List.of(new ToolCall("c2", "finish", "{\"summary\":\"끝\"}"))));
        ToolLoop.Result r = ToolLoop.run(llm, mapper, "CHAT_COMMAND", "sys", "user", tools, "finish", "cannot", 6, a -> null);
        assertThat(r.gaveUp()).isFalse();
        assertThat(r.finish().path("summary").asText()).isEqualTo("끝");
        assertThat(r.trail()).singleElement().extracting(ToolLoop.Trace::result).isEqualTo("{\"error\":\"reason이 비어 있습니다.\"}");
    }

    @Test
    void rejectedFinishGoesBackAsErrorAndTheLoopGoesOn() {
        llm.toolTurns.add(new AssistantTurn(null, List.of(new ToolCall("c1", "finish", "{\"summary\":\"틀림\"}"))));
        llm.toolTurns.add(new AssistantTurn(null, List.of(new ToolCall("c2", "finish", "{\"summary\":\"맞음\"}"))));
        ToolLoop.Result r = ToolLoop.run(llm, mapper, "CHAT_COMMAND", "sys", "user", tools, "finish", 6,
                a -> "틀림".equals(a.path("summary").asText()) ? "모르는 작업입니다: NOPE" : null);
        assertThat(r.finish().path("summary").asText()).isEqualTo("맞음");
        assertThat(r.trail()).singleElement().satisfies(t -> {
            assertThat(t.tool()).isEqualTo("finish");
            assertThat(t.result()).isEqualTo("{\"error\":\"모르는 작업입니다: NOPE\"}");
        });
        List<Map<String, Object>> second = llm.toolMessages.get(1);
        assertThat(second.get(3)).containsEntry("tool_call_id", "c1").containsEntry("content", "{\"error\":\"모르는 작업입니다: NOPE\"}");
    }

    @Test
    void stopsAfterMaxCalls() {
        for (int i = 0; i < 3; i++) llm.toolTurns.add(new AssistantTurn(null, List.of(new ToolCall("c" + i, "echo", "{}"))));
        assertThatThrownBy(() -> ToolLoop.run(llm, mapper, "CHAT_COMMAND", "sys", "user", tools, "finish", 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(ToolLoop.UNFINISHED);
    }
}
