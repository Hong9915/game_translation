package com.gametrans.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gametrans.josa.JosaHint;
import com.gametrans.josa.JosaHints;
import com.gametrans.llm.LlmClient;
import com.gametrans.llm.LlmClient.ToolSpec;
import com.gametrans.llm.Prompts;
import com.gametrans.llm.ToolLoop;
import com.gametrans.text.PlaceholderCheck;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * 명령으로 고치기의 GPT 쪽(설계 2026-10-08 §3). 시트 행과 사이드카를 쥔 도구 6개를 ToolLoop에 꽂는다. 행 전체는 보내지 않고
 * 개수와 예시만 돌려준다.
 *
 * <p>tool calling 흐름(예: "구미호를 여우로 바꾸고 조사도 맞춰줘"):
 * <pre>
 *   GPT → find_rows {contains:"구미호"}            서버: 맞는 행 수와 앞 8줄
 *   GPT → try_ops  {ops:[REPLACE 구미호→여우]}      서버: 저장 없이 시트에 돌려 본 결과 — 바뀌는 행 수, 예시, 조사·태그 경고
 *   GPT → try_ops  {ops:[REPLACE…, JOSA]}          (조사 경고를 보고 JOSA를 덧붙여 다시 시험)
 *   GPT → finish   {ops:[…], summary:"…"}           서버: 이 작업 목록으로 변경 전·후 미리 보기를 만든다
 * </pre>
 * 할 수 없는 명령은 cannot {reason}으로 끝낸다(→ 400). GPT는 글만 내고 끝낼 수 없어(tool_choice=required) 질문으로 끝나지 않는다.
 * GPT는 도구를 고르고 인자를 쓸 뿐, 행을 직접 고치지 않는다. 실제로 바꾸는 것은 서버의 Pipeline이고,
 * 저장은 검토자가 미리 보기에서 행을 고른 뒤 CommandFixService.fix가 한다.
 * 도구 이름·설명·인자 형식은 아래 tools()에, 언제 무엇을 부를지는 Prompts.COMMAND_SYSTEM("How to work")에 있다.
 */
@Component
public class CommandAgent {
    public static final String FIND_ROWS = "find_rows", TRY_OPS = "try_ops", CHECK_JOSA = "check_josa",
            FIND_TAG_MISMATCH = "find_tag_mismatch", FINISH = "finish", CANNOT = "cannot";
    static final int MAX_CALLS = 6;
    static final int SAMPLES = 20;
    static final int EXAMPLES = 8;
    static final int CHARS = 120;

    /** ops는 tagMismatch면 null. */
    public record Outcome(String summary, List<OpSpec> ops, boolean tagMismatch, List<ToolLoop.Trace> trail) {}

    private final LlmClient llm;
    private final ObjectMapper mapper;

    public CommandAgent(LlmClient llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    /** 명령 하나를 GPT와 주고받아 작업 목록(또는 태그 짝 검사)으로 바꾼다. 할 수 없는 명령이면 IllegalArgumentException(→ 400). */
    public Outcome run(String command, List<SheetRow> rows, Pipeline.Sidecar sidecar) {
        AtomicReference<List<OpSpec>> lastWorking = new AtomicReference<>();
        // system = 규칙·도구 쓰는 법, user = 명령 + 시트 샘플 20줄. 최대 MAX_CALLS번 주고받고, finish 인자는 finishProblem으로 검사한다.
        ToolLoop.Result r = ToolLoop.run(llm, mapper, "CHAT_COMMAND", Prompts.COMMAND_SYSTEM,
                Prompts.commandUser(command, samples(rows, command)), tools(rows, sidecar, lastWorking), FINISH, CANNOT, MAX_CALLS, this::finishProblem);
        // cannot은 GPT가 일부러 그만둔 것 — 앞서 시험이 행을 바꿨어도 미리 보기로 바꾸지 않는다
        if (r.gaveUp()) throw new IllegalArgumentException(r.text());
        if (r.finish() == null) {
            String text = r.text() == null || r.text().isBlank() ? null : r.text().strip();
            // 모델이 finish 대신 "원하시면 적용하겠습니다"처럼 확인을 묻고 끝내기도 한다 — 미리 보기가 곧 확인이니,
            // 앞서 try_ops로 행을 바꾼 작업 목록이 있으면 그것을 낸다. 없으면 그 글이 거절 까닭이다.
            if (lastWorking.get() != null) return new Outcome(text, lastWorking.get(), false, r.trail());
            throw new IllegalArgumentException(text == null ? "찾아 바꾸기·검사로 할 수 없는 명령입니다." : text);
        }
        // finish 인자: {summary, ops} 또는 {summary, tag_mismatch:true}
        String summary = r.finish().path("summary").asText("");
        if (r.finish().path("tag_mismatch").asBoolean(false)) return new Outcome(summary, null, true, r.trail());
        return new Outcome(summary, ops(r.finish().path("ops")), false, r.trail());
    }

    /** finish 인자가 틀리면 그 까닭(모델에 돌려줘 고치게 한다), 맞으면 null. */
    private String finishProblem(JsonNode finish) {
        if (finish.path("tag_mismatch").asBoolean(false)) return null;
        try {
            Pipeline.compile(ops(finish.path("ops")));
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage() == null ? "작업 목록이 올바르지 않습니다." : e.getMessage();
        }
    }

    /** lastWorking: try_ops에서 행을 하나라도 바꾼 마지막 작업 목록(모델이 finish 없이 끝낼 때 쓴다). */
    private List<ToolLoop.Tool> tools(List<SheetRow> rows, Pipeline.Sidecar sidecar, AtomicReference<List<OpSpec>> lastWorking) {
        // 작업 하나(OpSpec)의 JSON Schema — try_ops와 finish의 ops 인자가 이 꼴의 배열이다.
        Map<String, Object> op = Map.of("type", "object", "properties", Map.of(
                "kind", Map.of("type", "string", "enum", List.of(OpSpec.REPLACE, OpSpec.TAG_SPACING, OpSpec.JOSA, OpSpec.UNIFY_SAME_SOURCE, OpSpec.TERM)),
                "src", Map.of("type", "string"), "ko", Map.of("type", "string"),
                "filter", Map.of("type", List.of("object", "null"), "properties", Map.of(
                        "in", Map.of("type", "string", "enum", List.of("ko", "src")), "contains", Map.of("type", "string"))),
                "steps", Map.of("type", "array", "items", Map.of("type", "object", "properties", Map.of(
                        "find", Map.of("type", "string"), "replace", Map.of("type", "string"), "regex", Map.of("type", "boolean")),
                        "required", List.of("find", "replace", "regex")))),
                "required", List.of("kind"));
        Map<String, Object> ops = Map.of("type", "array", "items", op, "minItems", 1, "maxItems", 6);
        // 도구마다: tool(이름, 모델이 읽을 설명, 인자 JSON Schema, 서버에서 실행할 일)
        return List.of(
                // 행 찾기 — 규칙을 쓰기 전에 실제 문장 꼴(태그·숫자)을 본다
                tool(FIND_ROWS, "Rows of this sheet whose Korean (in=ko) or source (in=src) contains the text. Returns count and up to limit rows.",
                        Map.of("type", "object", "properties", Map.of("contains", Map.of("type", "string"),
                                "in", Map.of("type", "string", "enum", List.of("ko", "src")), "limit", Map.of("type", "integer", "maximum", EXAMPLES)),
                                "required", List.of("contains")),
                        a -> findRows(rows, a)),
                // 시험 실행 — 저장하지 않고 시트 전체에 돌려 결과를 요약한다. 모델은 이걸 보고 규칙을 고치거나 JOSA를 덧붙인다
                tool(TRY_OPS, "Run a pipeline on this sheet without saving. Returns changed row count, count per op, examples and warnings.",
                        Map.of("type", "object", "properties", Map.of("ops", ops), "required", List.of("ops")),
                        a -> tryOps(rows, sidecar, a, lastWorking)),
                // 문장 몇 개의 조사 검사 — Kiwi 사이드카
                tool(CHECK_JOSA, "Check particles (조사) of up to 20 Korean sentences.",
                        Map.of("type", "object", "properties", Map.of("texts", Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", 20)),
                                "required", List.of("texts")),
                        a -> checkJosa(sidecar, a)),
                // 태그·자리표시자 짝이 원문과 다른 행 — "태그 깨진 행 찾아줘" 같은 검사용
                tool(FIND_TAG_MISMATCH, "Rows whose tags/placeholders differ between source and Korean.",
                        Map.of("type", "object", "properties", Map.of()),
                        a -> tagMismatch(rows)),
                // 끝내기 — 실행할 일은 없다. ToolLoop가 이 이름을 보고 반복을 멈추고 인자를 돌려준다
                tool(FINISH, "Finish: the pipeline to preview (ops), or tag_mismatch true for the tag check. summary in Korean.",
                        Map.of("type", "object", "properties", Map.of("summary", Map.of("type", "string"), "ops", ops,
                                "tag_mismatch", Map.of("type", "boolean")), "required", List.of("summary")),
                        a -> "ok"),
                // 그만두기 — 뜻·문맥을 봐야 하는 명령. ToolLoop가 이 이름을 보고 reason을 들고 멈춘다
                tool(CANNOT, "Give up: the command cannot be done with these ops (it needs meaning, retranslation or row-by-row judgement). "
                                + "reason: one short Korean sentence why. Never use it to ask the reviewer a question.",
                        Map.of("type", "object", "properties", Map.of("reason", Map.of("type", "string")), "required", List.of("reason")),
                        a -> "ok"));
    }

    private String findRows(List<SheetRow> rows, JsonNode a) {
        String text = a.path("contains").asText("");
        boolean src = "src".equals(a.path("in").asText("ko"));
        int limit = Math.max(1, Math.min(EXAMPLES, a.path("limit").asInt(EXAMPLES)));
        List<SheetRow> hit = rows.stream().filter(r -> !text.isEmpty() && (src ? r.src() : r.ko()).contains(text)).toList();
        List<Map<String, Object>> shown = hit.stream().limit(limit)
                .map(r -> Map.<String, Object>of("row", r.rowNo(), "src", cut(r.src()), "ko", cut(r.ko()))).toList();
        return json(Map.of("count", hit.size(), "rows", shown));
    }

    private String tryOps(List<SheetRow> rows, Pipeline.Sidecar sidecar, JsonNode a, AtomicReference<List<OpSpec>> lastWorking) {
        List<OpSpec> tried = ops(a.path("ops"));
        Pipeline pipeline = Pipeline.compile(tried);
        OpSpec term = pipeline.term();
        if (term != null) {
            // 용어 바꾸기는 행마다 GPT를 부르므로 시험에서는 돌리지 않는다 — 대상 행 수와 지금 번역 예시만 돌려준다
            List<SheetRow> hit = rows.stream().filter(r -> TermSwap.target(r, term.src(), term.ko())).toList();
            if (!hit.isEmpty()) lastWorking.set(tried);
            List<Map<String, Object>> examples = hit.stream().limit(EXAMPLES)
                    .map(r -> Map.<String, Object>of("src", cut(r.src()), "ko", cut(r.ko()))).toList();
            return json(Map.of("rowsToRewrite", hit.size(), "examples", examples,
                    "note", "Each of these rows is rewritten by GPT when the preview is made. Call finish with this op."));
        }
        List<Pipeline.Change> changes = pipeline.run(rows, null, sidecar);
        if (!changes.isEmpty()) lastWorking.set(tried);
        Map<String, Integer> byOp = new LinkedHashMap<>(), warnings = new LinkedHashMap<>();
        List<String> warningExamples = new ArrayList<>();
        for (Pipeline.Change c : changes) {
            c.by().forEach(k -> byOp.merge(k, 1, Integer::sum));
            for (Pipeline.Warning w : c.warnings()) {
                warnings.merge(w.kind(), 1, Integer::sum);
                if (warningExamples.size() < 5) warningExamples.add(w.kind() + ": " + cut(c.after()) + " — " + w.text());
            }
        }
        List<Map<String, Object>> examples = changes.stream().limit(EXAMPLES)
                .map(c -> Map.<String, Object>of("before", cut(c.row().ko()), "after", cut(c.after()))).toList();
        return json(Map.of("changed", changes.size(), "byOp", byOp, "examples", examples, "warnings", warnings, "warningExamples", warningExamples));
    }

    private String checkJosa(Pipeline.Sidecar sidecar, JsonNode a) {
        List<String> texts = new ArrayList<>();
        for (JsonNode t : a.path("texts")) if (texts.size() < 20) texts.add(t.asText(""));
        List<List<JosaHint>> hints = sidecar.josa(texts);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i);
            out.add(Map.of("text", cut(t), "hints", hints.get(i).stream().map(h -> JosaHints.label(t, h)).toList()));
        }
        return json(Map.of("results", out));
    }

    private String tagMismatch(List<SheetRow> rows) {
        List<SheetRow> bad = rows.stream().filter(r -> !PlaceholderCheck.matches(r.src(), r.ko())).toList();
        List<Map<String, Object>> shown = bad.stream().limit(EXAMPLES)
                .map(r -> Map.<String, Object>of("row", r.rowNo(), "src", cut(r.src()), "ko", cut(r.ko()))).toList();
        return json(Map.of("count", bad.size(), "examples", shown));
    }

    private List<OpSpec> ops(JsonNode node) {
        if (node == null || !node.isArray()) throw new IllegalArgumentException("작업이 없습니다.");
        return mapper.convertValue(node, new TypeReference<List<OpSpec>>() {});
    }

    private ToolLoop.Tool tool(String name, String description, Map<String, Object> params, Function<JsonNode, String> f) {
        ToolSpec spec = new ToolSpec(name, description, params);
        return new ToolLoop.Tool() {
            @Override public ToolSpec spec() { return spec; }
            @Override public String call(JsonNode args) { return f.apply(args); }
        };
    }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private static String cut(String s) {
        if (s == null) return "";
        String one = s.replace("\n", "⏎");
        return one.length() <= CHARS ? one : one.substring(0, CHARS) + "…";
    }

    /** 명령에 든 낱말(구미호 등)이나 기호(-, ~, … 등)를 품은 번역을 먼저, 모자라면 시트 전체에서 고르게 고른다. */
    static List<String> samples(List<SheetRow> rows, String command) {
        Set<Integer> symbols = new LinkedHashSet<>();
        command.codePoints().filter(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c)).forEach(symbols::add);
        List<String> words = Arrays.stream(command.split("[^\\p{L}\\p{N}]+")).filter(w -> w.length() >= 2).toList();
        Set<String> out = new LinkedHashSet<>();
        for (SheetRow r : rows) {
            if (out.size() >= SAMPLES / 2) break;
            String ko = r.ko();
            if (words.stream().anyMatch(ko::contains) || ko.codePoints().anyMatch(symbols::contains)) out.add(ko);
        }
        int step = Math.max(1, rows.size() / SAMPLES);
        for (int i = 0; i < rows.size() && out.size() < SAMPLES; i += step) out.add(rows.get(i).ko());
        return new ArrayList<>(out);
    }
}
