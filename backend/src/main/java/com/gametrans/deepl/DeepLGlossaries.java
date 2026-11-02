package com.gametrans.deepl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 게임 단어장을 DeepL 단어장으로(설계 §2). DeepL 단어장은 고칠 수 없어서, 내용 해시가 달라지면 새로 만들고 옛 것은 지운다.
 * 중국어 원문용만 만든다 — 영어로 보내는 행에는 단어장이 없다.
 * API Free 계정은 단어장을 하나만 둘 수 있다(2026-10-26 456 "Too many glossaries"). 그래서 만들다 한도에 걸리면 계정의 단어장을
 * 모두 지우고 한 번 더 만든다. 같은 키를 로컬 미리보기·다른 게임이 함께 쓰므로, 적어 둔 단어장이 계정에 아직 있는지도 매번 본다.
 */
@Component
public class DeepLGlossaries {
    private static final Logger log = LoggerFactory.getLogger(DeepLGlossaries.class);
    private final JdbcTemplate jdbc;
    private final MtClient mt;

    public DeepLGlossaries(JdbcTemplate jdbc, MtClient mt) { this.jdbc = jdbc; this.mt = mt; }

    /** 이 게임의 지금 단어장에 맞는 DeepL 단어장 id. 쓸 항목이 없으면 null. */
    public synchronized String idFor(Long gameId, Map<String, String> terms) {
        Map<String, String> entries = clean(terms);
        if (entries.isEmpty()) return null;
        String hash = hash(entries);
        List<String[]> rows = jdbc.query("select glossary_id, content_hash, previous_glossary_id from deepl_glossary where game_id = ? and source_lang = 'ZH'",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}, gameId);
        String[] old = rows.isEmpty() ? null : rows.get(0);
        List<String> onAccount = mt.glossaryIds();
        // 내용이 같고 계정에 아직 있으면 그대로 쓴다. 다른 곳에서 지웠으면 새로 만든다.
        if (old != null && old[1].equals(hash) && onAccount.contains(old[0])) return old[0];
        String name = "game-" + gameId + "-" + hash.substring(0, 8);
        String id;
        boolean cleared = false;
        try {
            id = mt.createGlossary(name, "ZH", entries);
        } catch (MtException.GlossaryLimit e) {
            // 한도에 찼다 — 계정의 단어장을 비우고 한 번 더. 지운 단어장으로 번역 중이던 작업은 다음 묶음에서 idFor로 새 id를 받는다.
            log.warn("DeepL 단어장 한도 — 계정의 단어장 {}개를 지우고 다시 만든다", onAccount.size());
            for (String g : onAccount) {
                try { mt.deleteGlossary(g); }
                catch (RuntimeException x) { log.warn("DeepL 단어장 {}을 지우지 못했다: {}", g, x.getMessage()); }
            }
            id = mt.createGlossary(name, "ZH", entries);
            cleared = true;
        }
        // 바로 앞 단어장은 남긴다 — 그걸 받은 작업이 아직 묶음을 보내는 중일 수 있다. 두 번 앞 것을 지운다.
        // 계정을 비웠으면 앞 단어장도 이미 없다.
        String previous = cleared || old == null ? null : old[0];
        jdbc.update("""
                insert into deepl_glossary (game_id, source_lang, glossary_id, previous_glossary_id, content_hash) values (?, 'ZH', ?, ?, ?)
                on conflict (game_id, source_lang) do update set glossary_id = excluded.glossary_id, previous_glossary_id = excluded.previous_glossary_id,
                    content_hash = excluded.content_hash, created_at = now()
                """, gameId, id, previous, hash);
        if (!cleared && old != null && old[2] != null) {
            try { mt.deleteGlossary(old[2]); }
            catch (RuntimeException e) { log.warn("옛 DeepL 단어장 {}을 지우지 못했다: {}", old[2], e.getMessage()); }
        }
        return id;
    }

    /** DeepL이 거절하는 글자: 제어 문자(탭·줄바꿈 포함)와 줄·문단 구분자. */
    private static final java.util.regex.Pattern BAD = java.util.regex.Pattern.compile("[\\p{Cc}\\u2028\\u2029]");
    /** 앞뒤 공백 — 엑셀에서 붙여 넣으면 딸려 오는 줄바꿈 없는 공백(U+00A0)·BOM(U+FEFF)까지. */
    private static final java.util.regex.Pattern EDGE = java.util.regex.Pattern.compile("^[\\s\\p{Z}\\ufeff]+|[\\s\\p{Z}\\ufeff]+$");

    private static String trim(String s) { return s == null ? "" : EDGE.matcher(s).replaceAll(""); }

    /** DeepL이 거절하는 항목(빈 값, 제어 문자·구분자가 든 것)을 빼고 앞뒤 공백을 다듬는다. 순서를 지키는 새 맵. */
    static Map<String, String> clean(Map<String, String> terms) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : terms.entrySet()) {
            String k = trim(e.getKey());
            String v = trim(e.getValue());
            if (k.isEmpty() || v.isEmpty() || BAD.matcher(k).find() || BAD.matcher(v).find()) continue;
            out.put(k, v);
        }
        return out;
    }

    private static String hash(Map<String, String> entries) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(entries).forEach((k, v) -> sb.append(k).append('\t').append(v).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
