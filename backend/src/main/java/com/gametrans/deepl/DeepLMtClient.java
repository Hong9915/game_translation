package com.gametrans.deepl;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * DeepL API v2(번역·단어장). 표기 갈림 직역용 DeepLClient와 키는 같고 쓰임이 다르다 — 이쪽은 태그를 감싼 XML 문장과 게임 단어장을 보낸다.
 * API Free 키는 ":fx"로 끝나고 주소가 다르다.
 */
@Component
public class DeepLMtClient implements MtClient {
    private final RestClient client;
    private final String key;
    private final String base;

    @Autowired
    public DeepLMtClient(RestClient.Builder builder, @Value("${app.deepl.auth-key:}") String key) {
        this.client = builder.build();
        this.key = key == null ? "" : key.strip();
        this.base = this.key.endsWith(":fx") ? "https://api-free.deepl.com" : "https://api.deepl.com";
    }

    @Override public boolean configured() { return !key.isEmpty(); }

    @Override
    public List<String> translate(List<String> xmlTexts, String sourceLang, String glossaryId, List<String> instructions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", new ArrayList<>(xmlTexts));
        body.put("source_lang", sourceLang);
        body.put("target_lang", "KO");
        body.put("tag_handling", "xml");
        body.put("ignore_tags", List.of("x"));
        if (glossaryId != null) body.put("glossary_id", glossaryId);
        if (instructions != null && !instructions.isEmpty()) body.put("custom_instructions", instructions);
        JsonNode res;
        try {
            res = client.post().uri(base + "/v2/translate").header("Authorization", "DeepL-Auth-Key " + key)
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw new MtException("DeepL 번역 요청 실패: " + e.getMessage(), e);
        }
        JsonNode tr = res == null ? null : res.path("translations");
        if (tr == null || tr.size() != xmlTexts.size())
            throw new MtException("DeepL 응답 개수가 요청과 다릅니다: " + (tr == null ? 0 : tr.size()) + " / " + xmlTexts.size());
        List<String> out = new ArrayList<>();
        for (JsonNode t : tr) out.add(t.path("text").asText());
        return out;
    }

    @Override
    public String createGlossary(String name, String sourceLang, Map<String, String> entries) {
        StringBuilder tsv = new StringBuilder();
        entries.forEach((k, v) -> tsv.append(k).append('\t').append(v).append('\n'));
        Map<String, Object> body = Map.of("name", name, "source_lang", sourceLang.toLowerCase(), "target_lang", "ko",
                "entries", tsv.toString(), "entries_format", "tsv");
        try {
            JsonNode res = client.post().uri(base + "/v2/glossaries").header("Authorization", "DeepL-Auth-Key " + key)
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
            String id = res == null ? "" : res.path("glossary_id").asText("");
            if (id.isEmpty()) throw new MtException("DeepL 단어장 id를 받지 못했습니다.");
            return id;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 456) throw new MtException.GlossaryLimit("DeepL 단어장 수 한도: " + e.getMessage(), e);
            throw new MtException("DeepL 단어장 만들기 실패: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new MtException("DeepL 단어장 만들기 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteGlossary(String glossaryId) {
        try {
            client.delete().uri(base + "/v2/glossaries/" + glossaryId).header("Authorization", "DeepL-Auth-Key " + key)
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new MtException("DeepL 단어장 지우기 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> glossaryIds() {
        try {
            JsonNode res = client.get().uri(base + "/v2/glossaries").header("Authorization", "DeepL-Auth-Key " + key)
                    .retrieve().body(JsonNode.class);
            List<String> out = new ArrayList<>();
            if (res != null) for (JsonNode g : res.path("glossaries")) out.add(g.path("glossary_id").asText());
            return out;
        } catch (RestClientException e) {
            throw new MtException("DeepL 단어장 목록 실패: " + e.getMessage(), e);
        }
    }
}
