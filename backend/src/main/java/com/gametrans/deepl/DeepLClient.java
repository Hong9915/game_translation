package com.gametrans.deepl;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 중국어 구절의 DeepL 직역(중→한). 표기 갈림 카드에 참고로만 띄운다 — 판정·치환에는 쓰지 않는다.
 * 메모리 → DB(literal_cache, V21) → DeepL 순서로 찾고, DeepL에서 새로 받은 직역은 DB에 넣어 같은 구절을 다시 사지 않는다
 * (재시작·배포 뒤에도 남는다). 키가 비면 DeepL은 부르지 않고 저장된 직역만 돌려준다.
 * 요청이 실패하면 그 묶음은 빼고 돌려준다(다음에 다시 시도) — 직역이 없어도 화면은 그대로 쓸 수 있어야 한다.
 */
@Component
public class DeepLClient {
    private static final Logger log = LoggerFactory.getLogger(DeepLClient.class);

    /** DeepL 한 요청의 text 개수 상한. */
    static final int BATCH = 50;
    /** 구절 직역용이라 긴 문장은 받지 않는다(비용과 남용 방지). */
    static final int MAX_TEXT = 40;

    private final RestClient client;
    private final String key;
    private final String url;
    private final Map<String, String> remembered = new ConcurrentHashMap<>();
    private final LiteralStore store;

    @Autowired
    public DeepLClient(RestClient.Builder builder, @Value("${app.deepl.auth-key:}") String key, LiteralStore store) {
        this.client = builder.build();
        this.store = store;
        this.key = key == null ? "" : key.strip();
        // DeepL API Free 키는 ":fx"로 끝나고 주소가 다르다
        this.url = this.key.endsWith(":fx") ? "https://api-free.deepl.com/v2/translate" : "https://api.deepl.com/v2/translate";
    }

    /** 원문 → 직역. 받은 순서대로, 중복·빈 값·너무 긴 값은 빼고. 직역을 못 받은 원문은 결과에 없다. */
    public Map<String, String> literal(List<String> texts) {
        Map<String, String> out = new LinkedHashMap<>();
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        for (String t : texts) if (t != null && !t.isBlank() && t.strip().length() <= MAX_TEXT) wanted.add(t.strip());
        List<String> notInMemory = wanted.stream().filter(t -> !remembered.containsKey(t)).toList();
        if (!notInMemory.isEmpty()) remembered.putAll(store.load(notInMemory));
        if (!key.isEmpty()) {
            List<String> missing = notInMemory.stream().filter(t -> !remembered.containsKey(t)).toList();
            for (int i = 0; i < missing.size(); i += BATCH) fetch(missing.subList(i, Math.min(i + BATCH, missing.size())));
        }
        for (String t : wanted) { String ko = remembered.get(t); if (ko != null) out.put(t, ko); }
        return out;
    }

    private void fetch(List<String> batch) {
        try {
            JsonNode body = client.post().uri(url)
                    .header("Authorization", "DeepL-Auth-Key " + key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", new ArrayList<>(batch), "source_lang", "ZH", "target_lang", "KO"))
                    .retrieve().body(JsonNode.class);
            JsonNode tr = body == null ? null : body.path("translations");
            if (tr == null || tr.size() != batch.size()) { log.warn("DeepL 응답 개수가 요청과 다르다: {} / {}", tr == null ? 0 : tr.size(), batch.size()); return; }
            Map<String, String> got = new LinkedHashMap<>();
            for (int i = 0; i < batch.size(); i++) got.put(batch.get(i), tr.get(i).path("text").asText());
            remembered.putAll(got);
            store.save(got);
        } catch (RestClientException e) {
            log.warn("DeepL 직역 요청 실패({}개): {}", batch.size(), e.getMessage());
        }
    }
}
