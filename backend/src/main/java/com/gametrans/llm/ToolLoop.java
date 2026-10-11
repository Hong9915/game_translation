package com.gametrans.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gametrans.llm.LlmClient.AssistantTurn;
import com.gametrans.llm.LlmClient.ToolCall;
import com.gametrans.llm.LlmClient.ToolSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 범용 tool calling 반복기(2026-10-08). 모델이 도구를 부르면 실행해 결과를 role:tool로 돌려주고 다시 묻는다.
 * finishTool이 불리면 그 인자를(finishCheck가 오류를 내면 모델에 돌려주고 계속한다), giveUpTool이 불리면 그 reason을(gaveUp),
 * 도구 없이 글만 오면 그 글을 돌려준다. 도구 안 오류는 {"error":…}로 모델에 돌려 고치게 한다.
 *
 * <p>한 바퀴(round)는 이렇게 돈다.
 * <pre>
 *   ① 서버 → 모델: 지금까지의 대화(messages) + 쓸 수 있는 도구 목록(specs)
 *   ② 모델 → 서버: 글(content) 그리고/또는 도구 호출(tool_calls: 이름 + JSON 인자)
 *   ③ 서버: 도구를 실제로 실행하고, 결과를 role:"tool" 메시지로 대화에 붙인다
 *   ④ ①로 돌아가 다시 묻는다 — 모델은 앞 결과를 보고 다음 도구를 고른다
 * </pre>
 * 끝나는 경우는 넷이다: 모델이 finishTool을 부름(정상 끝), giveUpTool을 부름(거절), 도구 없이 글만 냄, maxCalls를 다 씀(UNFINISHED).
 * 요청은 tool_choice=required라 모델이 글만 내고 끝낼 수 없다 — 질문으로 끝나지 않게(2026-10-11, 프로드 "破甲 이거…" 명령에
 * GPT가 도구를 하나도 안 부르고 "원문에서 찾을까요?"로 끝냈다). 할 수 없다는 말은 giveUpTool로 한다.
 * 서버가 하는 일은 "도구를 실행해 결과를 돌려주는 것"뿐이고, 어떤 도구를 언제 부를지는 모델이 정한다.
 * 명령으로 고치기는 CommandAgent가 도구를 정의해 이 반복기에 꽂는다 — 다른 기능도 도구만 바꿔 쓸 수 있다.
 */
public final class ToolLoop {
    public static final String UNFINISHED = "GPT가 작업을 끝내지 못했습니다. 명령을 나눠 보세요.";
    static final int TRACE_CHARS = 300;

    /** 모델이 부를 수 있는 도구 하나. spec은 모델에 보여 줄 이름·설명·인자 형식(JSON Schema), call은 서버에서 실제로 하는 일. */
    public interface Tool {
        ToolSpec spec();
        /** 모델에 돌려줄 짧은 JSON. 실패는 RuntimeException(메시지가 모델에 간다). */
        String call(JsonNode args);
    }

    /** 화면 "GPT가 한 일" 한 줄. args·result는 TRACE_CHARS로 자른다. */
    public record Trace(String tool, String args, String result) {}
    /** finish = finishTool 인자(없으면 null), text = 모델의 마지막 글 또는 giveUpTool의 reason, gaveUp = giveUpTool로 끝남. */
    public record Result(JsonNode finish, String text, List<Trace> trail, boolean gaveUp) {}

    private ToolLoop() {}

    public static Result run(LlmClient llm, ObjectMapper mapper, String stage, String system, String user,
                             List<Tool> tools, String finishTool, int maxCalls) {
        return run(llm, mapper, stage, system, user, tools, finishTool, null, maxCalls, a -> null);
    }

    public static Result run(LlmClient llm, ObjectMapper mapper, String stage, String system, String user,
                             List<Tool> tools, String finishTool, int maxCalls, Function<JsonNode, String> finishCheck) {
        return run(llm, mapper, stage, system, user, tools, finishTool, null, maxCalls, finishCheck);
    }

    /**
     * finishCheck는 finishTool 인자의 오류 문구(맞으면 null)를 돌려준다. 오류면 {"error":…}로 모델에 돌려 다시 고치게 한다.
     * giveUpTool(없으면 null)은 인자 reason과 함께 반복을 끝낸다. reason이 비면 오류로 돌려준다.
     */
    public static Result run(LlmClient llm, ObjectMapper mapper, String stage, String system, String user,
                             List<Tool> tools, String finishTool, String giveUpTool, int maxCalls, Function<JsonNode, String> finishCheck) {
        // 도구 이름 → 실행기. 모델은 이름으로 도구를 부른다.
        Map<String, Tool> byName = new LinkedHashMap<>();
        for (Tool t : tools) byName.put(t.spec().name(), t);
        List<ToolSpec> specs = tools.stream().map(Tool::spec).toList();
        // 대화 기록. 매 바퀴 통째로 다시 보낸다(OpenAI chat은 상태가 없어 앞 대화를 서버가 들고 다녀야 한다).
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));   // 규칙·도구 쓰는 법
        messages.add(Map.of("role", "user", "content", user));       // 검토자의 명령 + 시트 샘플
        List<Trace> trail = new ArrayList<>();
        for (int round = 0; round < maxCalls; round++) {
            // ①② 모델에 묻는다. 모델은 도구를 부르거나(tool_calls) 글로 답한다.
            AssistantTurn turn = llm.chatTools(stage, List.copyOf(messages), specs);
            // 도구 없이 글만 왔으면 끝 — 부르는 쪽이 그 글을 어떻게 볼지(거절 등) 정한다.
            if (turn.calls() == null || turn.calls().isEmpty()) return new Result(null, turn.content(), trail, false);
            // 모델의 도구 호출을 대화에 그대로 남긴다. 다음 role:tool 메시지가 tool_call_id로 이 호출에 짝지어진다.
            Map<String, Object> assistant = new HashMap<>();
            assistant.put("role", "assistant");
            assistant.put("content", turn.content());
            assistant.put("tool_calls", turn.calls().stream().map(c -> Map.of("id", c.id(), "type", "function",
                    "function", Map.of("name", c.name(), "arguments", c.arguments()))).toList());
            messages.add(assistant);
            // ③ 부른 도구마다 실행한다(parallel_tool_calls=false라 보통 하나).
            for (ToolCall c : turn.calls()) {
                // 인자는 모델이 쓴 JSON 문자열이다. 깨져 있으면 null로 두고 아래에서 오류로 돌려준다.
                JsonNode args;
                try { args = mapper.readTree(c.arguments() == null || c.arguments().isBlank() ? "{}" : c.arguments()); }
                catch (JsonProcessingException e) { args = null; }
                String result;
                Tool tool = byName.get(c.name());
                // finish: 인자가 맞으면 여기서 끝, 틀리면 오류를 돌려주고 모델이 고쳐 다시 부르게 한다.
                if (finishTool.equals(c.name()) && args != null) {
                    String problem;
                    try { problem = finishCheck.apply(args); }
                    catch (RuntimeException e) { problem = e.getMessage() == null ? "오류" : e.getMessage(); }
                    if (problem == null) return new Result(args, turn.content(), trail, false);
                    result = error(mapper, problem);
                }
                // 그만두기: reason을 들고 끝낸다. 비어 있으면 까닭을 쓰라고 돌려준다.
                else if (c.name().equals(giveUpTool) && args != null) {
                    String reason = args.path("reason").asText("").strip();
                    if (!reason.isEmpty()) return new Result(null, reason, trail, true);
                    result = error(mapper, "reason이 비어 있습니다.");
                }
                else if (tool == null) result = error(mapper, "모르는 도구입니다: " + c.name());
                else if (args == null) result = error(mapper, "인자가 JSON이 아닙니다.");
                else {
                    // 실제 실행. 예외도 끊지 않고 {"error":…}로 돌려준다 — 모델이 보고 인자를 고쳐 다시 부른다.
                    try { result = tool.call(args); }
                    catch (RuntimeException e) { result = error(mapper, e.getMessage()); }
                }
                // 화면 "GPT가 한 일"에 남기고, 결과를 role:tool로 대화에 붙인다. ④ 다음 바퀴에 모델이 이 결과를 본다.
                trail.add(new Trace(c.name(), cut(c.arguments()), cut(result)));
                messages.add(Map.of("role", "tool", "tool_call_id", c.id(), "content", result));
            }
        }
        // maxCalls 바퀴 안에 finish가 없었다 — 끝없이 돌며 돈을 쓰지 않게 멈춘다.
        throw new IllegalArgumentException(UNFINISHED);
    }

    private static String error(ObjectMapper mapper, String message) {
        try { return mapper.writeValueAsString(Map.of("error", message == null ? "오류" : message)); }
        catch (JsonProcessingException e) { return "{\"error\":\"오류\"}"; }
    }

    private static String cut(String s) {
        if (s == null) return "";
        return s.length() <= TRACE_CHARS ? s : s.substring(0, TRACE_CHARS) + "…";
    }
}
