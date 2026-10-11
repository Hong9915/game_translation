package com.gametrans.deepl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DeepLClientTest {
    /** DB 대신 맵. */
    static class MapStore implements LiteralStore {
        final Map<String, String> saved = new java.util.LinkedHashMap<>();
        @Override public Map<String, String> load(java.util.Collection<String> texts) {
            Map<String, String> out = new java.util.HashMap<>();
            for (String t : texts) if (saved.containsKey(t)) out.put(t, saved.get(t));
            return out;
        }
        @Override public void save(Map<String, String> literal) { saved.putAll(literal); }
    }

    final MapStore store = new MapStore();
    private static String reply(String... texts) {
        StringBuilder sb = new StringBuilder("{\"translations\":[");
        for (int i = 0; i < texts.length; i++) sb.append(i > 0 ? "," : "").append("{\"detected_source_language\":\"ZH\",\"text\":\"").append(texts[i]).append("\"}");
        return sb.append("]}").toString();
    }

    @Test
    void translatesChineseToKoreanWithTheFreeEndpointForAFreeKey() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DeepLClient client = new DeepLClient(builder, "abc:fx", store);
        server.expect(requestTo("https://api-free.deepl.com/v2/translate")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "DeepL-Auth-Key abc:fx"))
                .andExpect(jsonPath("$.source_lang").value("ZH")).andExpect(jsonPath("$.target_lang").value("KO"))
                .andExpect(jsonPath("$.text[0]").value("随机")).andExpect(jsonPath("$.text[1]").value("陷阱"))
                .andRespond(withSuccess(reply("무작위", "함정"), MediaType.APPLICATION_JSON));
        assertThat(client.literal(List.of("随机", "陷阱"))).containsExactly(Map.entry("随机", "무작위"), Map.entry("陷阱", "함정"));
        server.verify();
    }

    @Test
    void usesTheProEndpointForAProKey() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DeepLClient client = new DeepLClient(builder, "pro-key", store);
        server.expect(requestTo("https://api.deepl.com/v2/translate")).andRespond(withSuccess(reply("무작위"), MediaType.APPLICATION_JSON));
        assertThat(client.literal(List.of("随机"))).containsEntry("随机", "무작위");
    }

    @Test
    void sendsAtMostFiftyTextsPerRequestAndRemembersWhatItGot() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DeepLClient client = new DeepLClient(builder, "abc:fx", store);
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 60; i++) texts.add("词" + i);
        String[] first = new String[50], second = new String[10];
        for (int i = 0; i < 50; i++) first[i] = "말" + i;
        for (int i = 0; i < 10; i++) second[i] = "말" + (50 + i);
        server.expect(jsonPath("$.text.length()").value(50)).andRespond(withSuccess(reply(first), MediaType.APPLICATION_JSON));
        server.expect(jsonPath("$.text.length()").value(10)).andRespond(withSuccess(reply(second), MediaType.APPLICATION_JSON));
        Map<String, String> out = client.literal(texts);
        assertThat(out).hasSize(60).containsEntry("词59", "말59");
        server.verify();
        // 두 번째 부름은 DeepL에 가지 않는다 — 기대한 요청이 더 없으니 가면 실패한다
        assertThat(client.literal(List.of("词0", "词0", "词59"))).containsExactly(Map.entry("词0", "말0"), Map.entry("词59", "말59"));
    }

    @Test
    void storedLiteralsAreUsedWithoutCallingDeepLAndNewOnesAreStored() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        store.saved.put("随机", "무작위");
        DeepLClient client = new DeepLClient(builder, "abc:fx", store);
        server.expect(jsonPath("$.text.length()").value(1)).andExpect(jsonPath("$.text[0]").value("陷阱"))
                .andRespond(withSuccess(reply("함정"), MediaType.APPLICATION_JSON));
        assertThat(client.literal(List.of("随机", "陷阱"))).containsExactly(Map.entry("随机", "무작위"), Map.entry("陷阱", "함정"));
        server.verify();
        assertThat(store.saved).containsEntry("陷阱", "함정");
    }

    @Test
    void withoutAKeyItStillShowsWhatIsStored() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        store.saved.put("随机", "무작위");
        assertThat(new DeepLClient(builder, "", store).literal(List.of("随机", "陷阱"))).containsExactly(Map.entry("随机", "무작위"));
        server.verify();
    }

    @Test
    void withoutAKeyItReturnsNothingAndCallsNobody() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        assertThat(new DeepLClient(builder, " ", store).literal(List.of("随机"))).isEmpty();
        server.verify();
    }

    @Test
    void aFailedRequestLeavesThoseTextsOutAndIsTriedAgainNextTime() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DeepLClient client = new DeepLClient(builder, "abc:fx", store);
        server.expect(ExpectedCount.once(), requestTo("https://api-free.deepl.com/v2/translate")).andRespond(withServerError());
        server.expect(ExpectedCount.once(), requestTo("https://api-free.deepl.com/v2/translate")).andRespond(withSuccess(reply("무작위"), MediaType.APPLICATION_JSON));
        assertThat(client.literal(List.of("随机"))).isEmpty();
        assertThat(client.literal(List.of("随机"))).containsEntry("随机", "무작위");
        server.verify();
    }

    @Test
    void ignoresBlankAndOverlongTexts() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DeepLClient client = new DeepLClient(builder, "abc:fx", store);
        server.expect(jsonPath("$.text.length()").value(1)).andRespond(withSuccess(reply("무작위"), MediaType.APPLICATION_JSON));
        assertThat(client.literal(List.of("随机", " ", "长".repeat(DeepLClient.MAX_TEXT + 1)))).containsOnlyKeys("随机");
        server.verify();
    }
}
