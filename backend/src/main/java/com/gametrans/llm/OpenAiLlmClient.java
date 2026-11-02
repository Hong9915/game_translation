package com.gametrans.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gametrans.config.AppProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class OpenAiLlmClient implements LlmClient {
    private final RestClient client;
    private final AppProperties props;
    private final ObjectMapper mapper;
    private final long backoffMillis;

    @Autowired
    private final UsageSink usage;

    @org.springframework.beans.factory.annotation.Autowired  // 생성자가 여럿이라 Spring이 쓸 것을 지정
    public OpenAiLlmClient(RestClient.Builder builder, AppProperties props, ObjectMapper mapper, UsageSink usage) {
        this(builder, props, mapper, 1000, usage);
    }

    OpenAiLlmClient(RestClient.Builder builder, AppProperties props, ObjectMapper mapper, long backoffMillis) {
        this(builder, props, mapper, backoffMillis, u -> { });
    }

    OpenAiLlmClient(RestClient.Builder builder, AppProperties props, ObjectMapper mapper, long backoffMillis, UsageSink usage) {
        this.usage = usage;
        this.client = builder.baseUrl(props.openai().baseUrl())
                .defaultHeader("Authorization", "Bearer " + props.openai().apiKey())
                .build();
        this.props = props;
        this.mapper = mapper;
        this.backoffMillis = backoffMillis;
    }

    @Override public List<TranslationResult> translateBatch(TranslateBatchRequest request) {
        return results(chatJson("TRANSLATE", Prompts.translateSystem(request), Prompts.translateUser(request.rows()), false));
    }

    @Override public List<TranslationResult> shortenBatch(List<ShortenRow> rows) {
        return results(chatJson("SHORTEN", Prompts.SHORTEN_SYSTEM, Prompts.shortenUser(rows), false));
    }

    @Override public List<String> shortenOptions(ShortenOptionsRequest request) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : chatJson("SHORTEN_OPTIONS", Prompts.shortenOptionsSystem(request), Prompts.shortenOptionsUser(request), false))
            out.add(n.path("ko").asText(""));
        return out;
    }

    @Override public List<TranslationResult> fixTermsBatch(List<FixTermsRow> rows) {
        return results(chatJson("FIX_TERMS", Prompts.FIX_TERMS_SYSTEM, Prompts.fixTermsUser(rows), false));
    }

    @Override public List<TranslationResult> termSwapBatch(List<FixTermsRow> rows) {
        return results(chatJson("TERM_SWAP", Prompts.TERM_SWAP_SYSTEM, Prompts.fixTermsUser(rows), false));
    }

    @Override public List<ProofreadResult> proofreadBatch(ProofreadBatchRequest request) {
        JsonNode items = chatJson("PROOFREAD", Prompts.proofreadSystem(request), Prompts.proofreadUser(request.rows()), false);
        List<ProofreadResult> out = new ArrayList<>();
        for (JsonNode n : items)
            out.add(new ProofreadResult(n.path("index").asText(), n.path("verdict").asText("KEEP"),
                    n.path("ko").isMissingNode() || n.path("ko").isNull() ? null : n.path("ko").asText(),
                    n.path("reason").isMissingNode() || n.path("reason").isNull() ? null : n.path("reason").asText()));
        return out;
    }

    @Override public boolean ping() {
        try {
            client.get().uri("/models/{m}", props.openai().model()).retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException e) { return false; }
    }

    @Override public List<PhraseCategory> classifyPhrases(List<PhraseInput> phrases) {
        List<PhraseCategory> out = new ArrayList<>();
        for (JsonNode n : chatJson("CLASSIFY_PHRASES", Prompts.CLASSIFY_SYSTEM, Prompts.classifyUser(phrases), true))
            out.add(new PhraseCategory(n.path("src").asText(), n.path("category").asText("")));
        return out;
    }

    @Override public List<TermDraft> draftTerms(TermDraftRequest req) {
        List<TermDraft> out = new ArrayList<>();
        for (JsonNode n : chatJson("DRAFT_TERMS", Prompts.DRAFT_TERMS_SYSTEM, Prompts.draftTermsUser(req), false))
            out.add(new TermDraft(n.path("src").asText(), n.path("ko").asText(""), n.path("reason").asText("")));
        return out;
    }

    /**
     * tool calling 한 번 — OpenAI chat/completions에 도구 목록을 실어 보내고, 모델이 부른 도구(tool_calls)와 글을 읽는다.
     * 도구를 실제로 실행하고 다시 묻는 반복은 ToolLoop가 한다. 사용량은 stage(예: CHAT_COMMAND)로 기록한다.
     */
    @Override public AssistantTurn chatTools(String stage, List<Map<String, Object>> messages, List<ToolSpec> tools) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        String model = props.openai().model();
        body.put("model", model);
        body.put("messages", messages);
        // 모델에 알려 주는 도구 목록: 이름·설명·인자 JSON Schema. 모델은 이것만 보고 무엇을 부를지 고른다.
        body.put("tools", tools.stream().map(t -> Map.of("type", "function", "function",
                Map.of("name", t.name(), "description", t.description(), "parameters", t.parameters()))).toList());
        body.put("tool_choice", "required");      // 매 턴 도구를 부른다 — 글만 내고(질문으로) 끝내지 못하게. 그만두기도 도구로 한다
        body.put("parallel_tool_calls", false);   // 한 번에 하나씩 — 앞 결과를 보고 다음을 고르게
        String effort = props.openai().reasoningEffort();
        if (effort != null && !effort.isBlank()) body.put("reasoning_effort", effort.trim());
        String raw = withRetry(() -> client.post().uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
        try {
            JsonNode root = mapper.readTree(raw);
            recordUsage(stage, root.path("model").asText(model), root.path("usage"));
            JsonNode msg = root.path("choices").path(0).path("message");
            // tool_calls[i] = {id, function:{name, arguments(JSON 문자열)}}. id는 결과(role:tool)를 짝지을 때 쓴다.
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode c : msg.path("tool_calls"))
                calls.add(new ToolCall(c.path("id").asText(), c.path("function").path("name").asText(), c.path("function").path("arguments").asText("{}")));
            String content = msg.path("content").isTextual() ? msg.path("content").asText() : null;
            return new AssistantTurn(content, calls);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("OpenAI 응답을 해석할 수 없습니다", e);
        }
    }

    private static List<TranslationResult> results(JsonNode items) {
        List<TranslationResult> out = new ArrayList<>();
        for (JsonNode n : items) out.add(new TranslationResult(n.path("index").asText(), n.path("ko").asText()));
        return out;
    }

    /** JSON 모드로 호출하고 응답 객체의 "items" 배열을 돌려준다. light=true면 가벼운 모델(추출·추천용). */
    JsonNode chatJson(String stage, String system, String user, boolean light) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        String model = light ? props.openai().lightModelOrDefault() : props.openai().model();
        body.put("model", model);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
        // 추론 모델은 기본(medium) 추론이 응답 시간의 대부분을 차지한다. 이 작업들은 단순해서 low로도 충분하다.
        String effort = light ? props.openai().lightReasoningEffort() : props.openai().reasoningEffort();
        if (effort != null && !effort.isBlank()) body.put("reasoning_effort", effort.trim());
        String raw = withRetry(() -> client.post().uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
        try {
            JsonNode root = mapper.readTree(raw);
            recordUsage(stage, root.path("model").asText(model), root.path("usage"));
            String content = root.path("choices").path(0).path("message").path("content").asText();
            JsonNode parsed = mapper.readTree(content);
            JsonNode items = parsed.path("items");
            return items.isArray() ? items : mapper.createArrayNode();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("OpenAI 응답을 해석할 수 없습니다", e);
        }
    }

    private void recordUsage(String stage, String model, JsonNode u) {
        if (u == null || u.isMissingNode()) return;
        usage.record(new UsageSink.Usage(stage, model, u.path("prompt_tokens").asInt(0), u.path("completion_tokens").asInt(0),
                u.path("completion_tokens_details").path("reasoning_tokens").asInt(0)));
    }

    private <T> T withRetry(Supplier<T> call) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try { return call.get(); }
            catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() != 429) throw e;
                last = e;
            }
            // 5xx, 타임아웃, 게이트웨이가 돌려주는 비-JSON 응답(octet-stream 등)은 일시적이라 보고 재시도
            catch (org.springframework.web.client.RestClientException e) { last = e; }
            boolean anotherAttemptWillFollow = attempt < 2;
            if (anotherAttemptWillFollow) {
                sleep(backoffMillis * (1L << attempt));
                if (Thread.currentThread().isInterrupted()) break;
            }
        }
        throw last;
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
