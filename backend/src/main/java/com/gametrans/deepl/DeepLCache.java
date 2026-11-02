package com.gametrans.deepl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** DeepL로 받은 번역(deepl_cache, V25). 키는 (원문 md5, 원문 언어). 단어장과 맞는지는 쓰는 쪽(DeepLTranslator)이 본다. */
@Component
public class DeepLCache {
    private static final int CHUNK = 1000;
    private final NamedParameterJdbcTemplate jdbc;

    public DeepLCache(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, String> load(Collection<String> srcs, String sourceLang) {
        Map<String, String> out = new HashMap<>();
        List<String> list = new ArrayList<>(srcs);
        for (int i = 0; i < list.size(); i += CHUNK) {
            List<String> hashes = list.subList(i, Math.min(i + CHUNK, list.size())).stream().map(DeepLCache::md5).toList();
            MapSqlParameterSource p = new MapSqlParameterSource("lang", sourceLang).addValue("hashes", hashes);
            jdbc.query("select src, ko from deepl_cache where source_lang = :lang and src_hash in (:hashes)", p,
                    rs -> { out.put(rs.getString("src"), rs.getString("ko")); });
        }
        return out;
    }

    public void save(Map<String, String> srcToKo, String sourceLang) {
        if (srcToKo.isEmpty()) return;
        MapSqlParameterSource[] batch = srcToKo.entrySet().stream()
                .map(e -> new MapSqlParameterSource("hash", md5(e.getKey())).addValue("src", e.getKey()).addValue("lang", sourceLang).addValue("ko", e.getValue()))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                insert into deepl_cache (src_hash, source_lang, src, ko) values (:hash, :lang, :src, :ko)
                on conflict (src_hash, source_lang) do update set src = excluded.src, ko = excluded.ko, updated_at = now()
                """, batch);
    }

    static String md5(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
