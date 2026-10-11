package com.gametrans.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gametrans.config.AppProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

class OpenAiLlmClientTest {
    AppProperties props = new AppProperties(
            new AppProperties.OpenAi("sk-test", "gpt-5", "https://api.openai.com/v1", "low", "gpt-5-mini", "minimal", "gpt-5=1.25/10"),
            new AppProperties.Storage("local", "build/x", "", "", ""),
            new AppProperties.Translate(30, 1.0), 50, "*");

    private static String completion(String jsonContent) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + com.fasterxml.jackson.databind.json.JsonMapper.builder().build().valueToTree(jsonContent).toString() + "}}]}";
    }

    @Test
    void translateBatchSendsJsonModeAndParsesItems() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiLlmClient client = new OpenAiLlmClient(builder, props, new ObjectMapper(), 0);

        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-test"))
                .andExpect(jsonPath("$.model").value("gpt-5"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.temperature").doesNotExist())
                .andExpect(jsonPath("$.reasoning_effort").value("low"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("烈焰之剑")))
                .andRespond(withSuccess(completion("{\"items\":[{\"index\":\"ItemText!2\",\"ko\":\"화염의 검\"}]}"), MediaType.APPLICATION_JSON));

        List<LlmClient.TranslationResult> out = client.translateBatch(new LlmClient.TranslateBatchRequest(
                "EN_FIRST", "해요체", Map.of("烈焰之剑", "화염의 검"),
                List.of(new LlmClient.TranslateRow("ItemText!2", "烈焰之剑", "Blazing Sword")), List.of()));

        assertThat(out).containsExactly(new LlmClient.TranslationResult("ItemText!2", "화염의 검"));
        server.verify();
    }

    /** 응답의 usage를 단계·모델과 함께 기록한다. usage가 없는 응답은 조용히 넘어간다. */
    @Test
    void recordsTokenUsageFromResponse() {
        List<UsageSink.Usage> seen = new java.util.ArrayList<>();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiLlmClient client = new OpenAiLlmClient(builder, props, new ObjectMapper(), 0, seen::add);
        String withUsage = "{\"model\":\"gpt-5-2026-01-01\",\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":300,\"completion_tokens_details\":{\"reasoning_tokens\":120}},"
                + "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"items\\\":[]}\"}}]}";
        server.expect(requestTo("https://api.openai.com/v1/chat/completions")).andRespond(withSuccess(withUsage, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.openai.com/v1/chat/completions")).andRespond(withSuccess(completion("{\"items\":[]}"), MediaType.APPLICATION_JSON));
        client.translateBatch(new LlmClient.TranslateBatchRequest("CN_ONLY", null, Map.of(), List.of(new LlmClient.TranslateRow("a", "你好", null)), List.of()));
        client.extractTerms(List.of("你好"), java.util.Map.of());
        assertThat(seen).containsExactly(new UsageSink.Usage("TRANSLATE", "gpt-5-2026-01-01", 1200, 300, 120));
    }

    /** 후보는 직역·의역·혼합이고 음역은 금지한다. */
    @Test
    void suggestPromptAsksForLiteralNotTransliteration() {
        assertThat(Prompts.SUGGEST_SYSTEM).contains("LITERAL", "MEANING", "MIXED")
                .contains("never on the sound")
                .doesNotContain("\"style\":\"TRANSLITERATION\"");
    }

    /** 후보 뒤에 붙은 괄호 설명은 떼어내고, 원문에 괄호가 있을 때만 그대로 둔다. */
    @Test
    void suggestCandidatesDropParentheticalGloss() {
        assertThat(Prompts.SUGGEST_SYSTEM).contains("in parentheses");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiLlmClient client = new OpenAiLlmClient(builder, props, new ObjectMapper(), 0);
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(completion("{\"items\":[{\"src\":\"已满\",\"candidates\":["
                        + "{\"text\":\"가득 참\",\"style\":\"LITERAL\"},{\"text\":\"만족(가득 찬 상태)\",\"style\":\"MEANING\"},{\"text\":\"풀 （가득）\",\"style\":\"MIXED\"}]},"
                        + "{\"src\":\"礼包(大)\",\"candidates\":[{\"text\":\"패키지(대)\",\"style\":\"MEANING\"}]}]}"), MediaType.APPLICATION_JSON));

        List<LlmClient.CandidateSuggestion> out = client.suggestCandidates(List.of(new LlmClient.TermInput("已满", "COMMON"), new LlmClient.TermInput("礼包(大)", "ITEM")));

        assertThat(out.get(0).candidates()).extracting(LlmClient.Candidate::text).containsExactly("가득 참", "만족", "풀");
        assertThat(out.get(1).candidates()).extracting(LlmClient.Candidate::text).containsExactly("패키지(대)");
    }

    /** 추출·후보 추천은 가벼운 모델과 그 모델용 추론 강도로 보낸다. */
    @Test
    void extractAndSuggestUseTheLightModel() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiLlmClient client = new OpenAiLlmClient(builder, props, new ObjectMapper(), 0);
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(jsonPath("$.model").value("gpt-5-mini"))
                .andExpect(jsonPath("$.reasoning_effort").value("minimal"))
                .andRespond(withSuccess(completion("{\"items\":[{\"src\":\"烈焰之剑\",\"type\":\"ITEM\"}]}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(jsonPath("$.model").value("gpt-5-mini"))
                .andRespond(withSuccess(completion("{\"items\":[]}"), MediaType.APPLICATION_JSON));
        assertThat(client.extractTerms(List.of("烈焰之剑"), java.util.Map.of())).hasSize(1);
        assertThat(client.suggestCandidates(List.of(new LlmClient.TermInput("烈焰之剑", "ITEM")))).isEmpty();
        server.verify();
    }

    /** 비추론 모델(gpt-4.1 등)은 reasoning_effort를 받으면 400이 나므로 빈 값이면 보내지 않는다. */
    @Test
    void omitsReasoningEffortWhenBlank() {
        AppProperties noEffort = new AppProperties(
                new AppProperties.OpenAi("sk-test", "gpt-4.1", "https://api.openai.com/v1", "", "", "", ""),
                props.storage(), props.translate(), 50, "*");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiLlmClient client = new OpenAiLlmClient(builder, noEffort, new ObjectMapper(), 0);
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(jsonPath("$.model").value("gpt-4.1"))
                .andExpect(jsonPath("$.reasoning_effort").doesNotExist())
                .andRespond(withSuccess(completion("{\"items\":[]}"), MediaType.APPLICATION_JSON));
        assertThat(client.extractTerms(List.of("x"), java.util.Map.of())).isEmpty();
        server.verify();
    }

    @Test
    void retriesOnServerErrorThenSucceeds() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiLlmClient client = new OpenAiLlmClient(builder, props, new ObjectMapper(), 0);
        server.expect(requestTo("https://api.openai.com/v1/chat/completions")).andRespond(withServerError());
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(completion("{\"items\":[{\"src\":\"烈焰之剑\",\"type\":\"ITEM\"}]}"), MediaType.APPLICATION_JSON));

        List<LlmClient.TermExtraction> out = client.extractTerms(List.of("烈焰之剑"), java.util.Map.of());
        assertThat(out).containsExactly(new LlmClient.TermExtraction("烈焰之剑", "ITEM"));
        server.verify();
    }

    @Test
    void suggestAndShortenParseTheirShapes() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiLlmClient client = new OpenAiLlmClient(builder, props, new ObjectMapper(), 0);
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(completion("{\"items\":[{\"src\":\"烈焰之剑\",\"candidates\":[{\"text\":\"열염지검\",\"style\":\"TRANSLITERATION\"},{\"text\":\"화염의 검\",\"style\":\"MEANING\"}]}]}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(completion("{\"items\":[{\"index\":\"ItemText!3\",\"ko\":\"화염 장검\"}]}"), MediaType.APPLICATION_JSON));

        List<LlmClient.CandidateSuggestion> s = client.suggestCandidates(List.of(new LlmClient.TermInput("烈焰之剑", "ITEM")));
        assertThat(s.get(0).candidates()).hasSize(2);
        List<LlmClient.TranslationResult> sh = client.shortenBatch(List.of(new LlmClient.ShortenRow("ItemText!3", "烈焰之剑", "화염의 힘이 깃든 장검", 6)));
        assertThat(sh.get(0).ko()).isEqualTo("화염 장검");
        server.verify();
    }

    @Test
    void allAttemptsFailingPropagatesAfterThreeRequests() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiLlmClient client = new OpenAiLlmClient(builder, props, new ObjectMapper(), 0);
        server.expect(requestTo("https://api.openai.com/v1/chat/completions")).andRespond(withServerError());
        server.expect(requestTo("https://api.openai.com/v1/chat/completions")).andRespond(withServerError());
        server.expect(requestTo("https://api.openai.com/v1/chat/completions")).andRespond(withServerError());

        assertThatThrownBy(() -> client.extractTerms(List.of("烈焰之剑"), java.util.Map.of()))
                .isInstanceOf(HttpServerErrorException.class);
        server.verify();
    }

    @Test
    void clientErrorIsNotRetried() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiLlmClient client = new OpenAiLlmClient(builder, props, new ObjectMapper(), 0);
        server.expect(requestTo("https://api.openai.com/v1/chat/completions")).andRespond(withBadRequest());

        assertThatThrownBy(() -> client.extractTerms(List.of("烈焰之剑"), java.util.Map.of()))
                .isInstanceOf(HttpClientErrorException.class);
        server.verify();
    }

    @Test
    void retriesOnTooManyRequestsThenSucceeds() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiLlmClient client = new OpenAiLlmClient(builder, props, new ObjectMapper(), 0);
        server.expect(requestTo("https://api.openai.com/v1/chat/completions")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(completion("{\"items\":[{\"src\":\"烈焰之剑\",\"type\":\"ITEM\"}]}"), MediaType.APPLICATION_JSON));

        List<LlmClient.TermExtraction> out = client.extractTerms(List.of("烈焰之剑"), java.util.Map.of());
        assertThat(out).containsExactly(new LlmClient.TermExtraction("烈焰之剑", "ITEM"));
        server.verify();
    }

    @Test
    void chatToolsSendsToolsAndParsesToolCalls() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiLlmClient client = new OpenAiLlmClient(builder, props, new ObjectMapper(), 0);
        String body = """
            {"model":"gpt-5-mini","choices":[{"message":{"role":"assistant","content":null,
              "tool_calls":[{"id":"call_1","type":"function","function":{"name":"try_ops","arguments":"{\\"ops\\":[]}"}}]}}],
             "usage":{"prompt_tokens":10,"completion_tokens":5}}
            """;
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(jsonPath("$.tools[0].type").value("function"))
                .andExpect(jsonPath("$.tools[0].function.name").value("try_ops"))
                .andExpect(jsonPath("$.tool_choice").value("required"))
                .andExpect(jsonPath("$.parallel_tool_calls").value(false))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        LlmClient.AssistantTurn t = client.chatTools("CHAT_COMMAND",
                List.of(Map.of("role", "system", "content", "s"), Map.of("role", "user", "content", "u")),
                List.of(new LlmClient.ToolSpec("try_ops", "시험", Map.of("type", "object"))));
        assertThat(t.content()).isNull();
        assertThat(t.calls()).containsExactly(new LlmClient.ToolCall("call_1", "try_ops", "{\"ops\":[]}"));
        server.verify();
    }
}
