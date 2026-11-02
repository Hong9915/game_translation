package com.gametrans.deepl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DeepLMtClientTest {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    void sendsXmlTextsWithTheGlossaryAndReadsTheTranslationsInOrder() {
        DeepLMtClient client = new DeepLMtClient(builder, "abc:fx");
        server.expect(requestTo("https://api-free.deepl.com/v2/translate")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "DeepL-Auth-Key abc:fx"))
                .andExpect(jsonPath("$.text[0]").value("伤害<x i=\"0\"/>")).andExpect(jsonPath("$.source_lang").value("ZH"))
                .andExpect(jsonPath("$.target_lang").value("KO")).andExpect(jsonPath("$.tag_handling").value("xml"))
                .andExpect(jsonPath("$.ignore_tags[0]").value("x")).andExpect(jsonPath("$.glossary_id").value("g1"))
                .andExpect(jsonPath("$.custom_instructions[0]").value("짧게"))
                .andRespond(withSuccess("{\"translations\":[{\"text\":\"피해<x i=\\\"0\\\"/>\"}]}", MediaType.APPLICATION_JSON));
        assertThat(client.translate(List.of("伤害<x i=\"0\"/>"), "ZH", "g1", List.of("짧게"))).containsExactly("피해<x i=\"0\"/>");
        server.verify();
    }

    @Test
    void leavesTheGlossaryAndInstructionsOutWhenThereAreNone() {
        DeepLMtClient client = new DeepLMtClient(builder, "pro-key");
        server.expect(requestTo("https://api.deepl.com/v2/translate")).andExpect(jsonPath("$.glossary_id").doesNotExist())
                .andExpect(jsonPath("$.custom_instructions").doesNotExist())
                .andExpect(jsonPath("$.source_lang").value("EN"))
                .andRespond(withSuccess("{\"translations\":[{\"text\":\"검\"}]}", MediaType.APPLICATION_JSON));
        assertThat(client.translate(List.of("Sword"), "EN", null, List.of())).containsExactly("검");
    }

    @Test
    void aFailedRequestBecomesAnMtException() {
        DeepLMtClient client = new DeepLMtClient(builder, "abc:fx");
        server.expect(requestTo("https://api-free.deepl.com/v2/translate")).andRespond(withBadRequest());
        assertThatThrownBy(() -> client.translate(List.of("a"), "ZH", null, List.of())).isInstanceOf(MtException.class);
    }

    @Test
    void aShortAnswerBecomesAnMtException() {
        DeepLMtClient client = new DeepLMtClient(builder, "abc:fx");
        server.expect(requestTo("https://api-free.deepl.com/v2/translate"))
                .andRespond(withSuccess("{\"translations\":[{\"text\":\"하나\"}]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.translate(List.of("一", "二"), "ZH", null, List.of())).isInstanceOf(MtException.class);
    }

    @Test
    void createsAGlossaryFromTabSeparatedEntriesAndDeletesIt() {
        DeepLMtClient client = new DeepLMtClient(builder, "abc:fx");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("射手", "사수"); entries.put("红色", "빨간색");
        server.expect(requestTo("https://api-free.deepl.com/v2/glossaries")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.source_lang").value("zh")).andExpect(jsonPath("$.target_lang").value("ko"))
                .andExpect(jsonPath("$.entries").value("射手\t사수\n红色\t빨간색\n")).andExpect(jsonPath("$.entries_format").value("tsv"))
                .andRespond(withSuccess("{\"glossary_id\":\"g-77\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api-free.deepl.com/v2/glossaries/g-77")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess());
        assertThat(client.createGlossary("game-3", "ZH", entries)).isEqualTo("g-77");
        client.deleteGlossary("g-77");
        server.verify();
    }

    @Test
    void aFullAccountIsAGlossaryLimitAndTheListGivesIds() {
        DeepLMtClient client = new DeepLMtClient(builder, "abc:fx");
        server.expect(requestTo("https://api-free.deepl.com/v2/glossaries")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatusCode.valueOf(456)).body("{\"message\":\"Too many glossaries\"}").contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api-free.deepl.com/v2/glossaries")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"glossaries\":[{\"glossary_id\":\"g-1\",\"name\":\"game-3\"}]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.createGlossary("game-2", "ZH", Map.of("射手", "사수"))).isInstanceOf(MtException.GlossaryLimit.class);
        assertThat(client.glossaryIds()).containsExactly("g-1");
        server.verify();
    }

    @Test
    void isConfiguredOnlyWithAKey() {
        assertThat(new DeepLMtClient(builder, "").configured()).isFalse();
        assertThat(new DeepLMtClient(builder, " k ").configured()).isTrue();
    }
}
