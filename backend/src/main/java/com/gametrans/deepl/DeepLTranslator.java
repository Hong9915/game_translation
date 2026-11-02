package com.gametrans.deepl;

import com.gametrans.glossary.EffectiveGlossary;
import com.gametrans.glossary.GlossaryEntry;
import com.gametrans.glossary.GlossaryPrompt;
import com.gametrans.text.GlossaryCheck;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * DeepL + 게임 단어장 번역(설계 2026-10-12 §1). 문장 통째 단어장 → DeepL 캐시(단어장과 맞을 때만) → 같은 원문은 한 번만 DeepL.
 * 번역 메모리는 부르는 쪽이 먼저 본다(파일 번역·빠른 번역은 보고, 행 번역은 건너뛴다).
 * 태그는 DeepLTags SMART로 보내고, 깨끗하지 않으면 WRAP_ALL로 한 번 더. 둘 다 안 되면 태그가 맞는 쪽을 problem과 함께 돌려준다.
 */
@Component
public class DeepLTranslator {
    static final int BATCH = 50;
    static final int MAX_CHARS = 30000;

    public record Item(String key, String cn, String en) {}
    public enum Source { GLOSSARY, CACHE, DEEPL }
    /** problem: null | "TAG"(태그 어긋남) | "HAN"(중국어 남음). */
    public record Outcome(String ko, Source source, String problem) {}
    /** sentChars: DeepL에 보낸 글자 수(감싼 XML 기준, 재시도 포함). cachedItems: 캐시로 채운 항목 수. failedTexts: DeepL이 거절해 결과가 없는 항목 수(줄 하나만 거절돼도 그 항목은 결과 없음).
     *  lastError: 마지막으로 거절된 요청의 이유(없으면 null). */
    public record Result(Map<String, Outcome> byKey, int sentChars, int cachedItems, int failedTexts, String lastError) {}

    private final MtClient mt;
    private final DeepLCache cache;
    private final DeepLGlossaries glossaries;
    private final EffectiveGlossary effective;
    private final com.gametrans.game.GameRepository games;

    public DeepLTranslator(MtClient mt, DeepLCache cache, DeepLGlossaries glossaries, EffectiveGlossary effective, com.gametrans.game.GameRepository games) {
        this.mt = mt; this.cache = cache; this.glossaries = glossaries; this.effective = effective; this.games = games;
    }

    /** 이 게임의 DeepL 지시문(줄마다 하나). 없으면 빈 목록. */
    public List<String> instructionsFor(Long gameId) {
        return games.findById(gameId).map(g -> com.gametrans.game.DeepLInstructions.lines(g.getDeeplInstructions())).orElse(List.of());
    }

    /**
     * 캐시 칸 이름. 지시문이 있으면 언어 뒤에 지시문 해시를 붙인다 — 지시문이 다르면 같은 원문도 번역이 달라지므로,
     * 다른 지시문(또는 지시문 없이)으로 받은 번역을 다시 쓰지 않는다.
     */
    static String cacheLang(String lang, List<String> instructions) {
        if (instructions.isEmpty()) return lang;
        try {
            byte[] d = java.security.MessageDigest.getInstance("MD5").digest(String.join("\n", instructions).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return lang + "#" + java.util.HexFormat.of().formatHex(d).substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean configured() { return mt.configured(); }

    public static String problemNote(String problem) {
        return "HAN".equals(problem) ? "DeepL: 중국어 남음" : "DeepL: 태그 어긋남";
    }

    /** 유효 단어장 SRC(ko 있음)만. 구 용어(game_term)는 옛 표기가 섞여 있어 넣지 않는다(2026-10-13 결정). 메모는 뗀다. */
    public Map<String, String> termsFor(Long gameId) {
        Map<String, String> out = new LinkedHashMap<>();
        GlossaryPrompt.srcTerms(effective.of(gameId)).forEach((k, v) -> out.put(k, GlossaryCheck.koOf(v)));
        return out;
    }

    /** 줄바꿈(&lt;br/&gt;·글자 그대로의 \n·실제 줄바꿈)과 그 앞뒤 공백. DeepL은 줄바꿈 표시를 문장 맨 앞으로 옮기곤 해서 줄마다 따로 보낸다. */
    static final Pattern LINE_BREAK = Pattern.compile("[ \\t]*(?:<br\\s*/?>|\\\\n|\\r?\\n)[ \\t]*", Pattern.CASE_INSENSITIVE);

    /** parts 사이사이에 breaks가 원래 글자 그대로 들어간다. parts.size() == breaks.size() + 1. */
    record Lines(List<String> parts, List<String> breaks) {
        static Lines of(String s) {
            List<String> parts = new ArrayList<>(), breaks = new ArrayList<>();
            Matcher m = LINE_BREAK.matcher(s);
            int pos = 0;
            while (m.find()) { parts.add(s.substring(pos, m.start())); breaks.add(m.group()); pos = m.end(); }
            parts.add(s.substring(pos));
            return new Lines(parts, breaks);
        }
    }

    public Result translate(Long gameId, List<Item> items, String sourceLang) {
        Map<String, Outcome> byKey = new LinkedHashMap<>();
        Map<String, String> terms = termsFor(gameId);
        List<String> instructions = instructionsFor(gameId);
        Map<String, String> whole = new HashMap<>();
        for (GlossaryEntry e : effective.of(gameId))
            if (e.getKind() == GlossaryEntry.Kind.SRC && e.getMatchMode() == GlossaryEntry.MatchMode.WHOLE && e.getKo() != null && !e.getKo().isBlank())
                whole.put(e.getFind().strip(), e.getKo().strip());

        // 항목마다 줄로 나누고, 언어별로 보낼 줄(같은 줄은 한 번)을 모은다
        record Plan(String key, String lang, Lines lines) {}
        List<Plan> plans = new ArrayList<>();
        Map<String, java.util.Set<String>> pending = new LinkedHashMap<>();
        for (Item it : items) {
            String cn = it.cn() == null ? "" : it.cn();
            if (whole.containsKey(cn.strip())) { byKey.put(it.key(), new Outcome(whole.get(cn.strip()), Source.GLOSSARY, null)); continue; }
            boolean english = "EN".equals(sourceLang) && it.en() != null && !it.en().isBlank();
            String lang = english ? "EN" : "ZH";
            String text = english ? it.en().strip() : cn;
            if (text.isBlank()) continue;
            Lines lines = Lines.of(text);
            plans.add(new Plan(it.key(), lang, lines));
            for (String part : lines.parts())
                if (!part.isBlank()) pending.computeIfAbsent(lang, k -> new java.util.LinkedHashSet<>()).add(part);
        }

        // 언어 → 줄 → 결과
        Map<String, Map<String, Outcome>> byLine = new HashMap<>();
        int sentChars = 0, failedTexts = 0;
        String lastError = null;
        for (Map.Entry<String, java.util.Set<String>> byLang : pending.entrySet()) {
            String lang = byLang.getKey();
            Map<String, Outcome> got = byLine.computeIfAbsent(lang, k -> new HashMap<>());
            Map<String, String> cached = cache.load(byLang.getValue(), cacheLang(lang, instructions));
            List<String> toSend = new ArrayList<>();
            for (String line : byLang.getValue()) {
                String ko = cached.get(line);
                boolean fits = ko != null && DeepLTags.clean(line, ko)
                        && ("EN".equals(lang) || GlossaryCheck.missing(line, ko, terms).isEmpty());
                if (fits) got.put(line, new Outcome(ko, Source.CACHE, null));
                else toSend.add(line);
            }
            if (toSend.isEmpty()) continue;
            if (!mt.configured()) throw new IllegalStateException("DeepL 키가 없습니다. DEEPL_AUTH_KEY를 설정하세요.");
            for (List<String> chunk : requests(toSend)) {
                // 묶음마다 다시 묻는다 — 무료 계정은 단어장이 하나뿐이라 다른 게임·로컬 미리보기가 중간에 지울 수 있다(내용이 같고 계정에 있으면 DB만 본다)
                String glossaryId = "ZH".equals(lang) ? glossaries.idFor(gameId, terms) : null;
                // 요청 하나가 거절돼도(413·429 등) 그 원문만 결과 없이 두고 나머지는 계속한다. 받은 것은 요청마다 바로 캐시한다 —
                // 뒤 요청이 실패해도 이미 산 번역을 다음 실행에서 다시 사지 않게.
                Sent first;
                try { first = send(chunk, DeepLTags.Mode.SMART, lang, glossaryId, instructions); }
                catch (MtException e) { lastError = e.getMessage(); continue; }
                sentChars += first.chars();
                List<String> retry = new ArrayList<>();
                for (String src : chunk) if (!DeepLTags.clean(src, first.out().get(src))) retry.add(src);
                Sent second = new Sent(Map.of(), 0);
                if (!retry.isEmpty()) {
                    try { second = send(retry, DeepLTags.Mode.WRAP_ALL, lang, glossaryId, instructions); } catch (MtException e) { /* 첫 결과로 고른다 */ }
                }
                sentChars += second.chars();
                Map<String, String> fresh = new LinkedHashMap<>();
                for (String src : chunk) {
                    Outcome o = pick(src, first.out().get(src), second.out().get(src));
                    if (o.problem() == null) fresh.put(src, o.ko());
                    got.put(src, o);
                }
                cache.save(fresh, cacheLang(lang, instructions));
            }
        }

        // 줄 결과를 원래 줄바꿈으로 다시 잇는다. 한 줄이라도 결과가 없으면(거절) 그 항목은 결과 없이 둔다.
        int cachedItems = 0;
        for (Plan p : plans) {
            Map<String, Outcome> got = byLine.getOrDefault(p.lang(), Map.of());
            StringBuilder ko = new StringBuilder();
            boolean allCached = true, missing = false;
            String problem = null;
            List<String> parts = p.lines().parts();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) ko.append(p.lines().breaks().get(i - 1));
                String part = parts.get(i);
                if (part.isBlank()) { ko.append(part); continue; }
                Outcome o = got.get(part);
                if (o == null) { missing = true; break; }
                ko.append(o.ko());
                allCached &= o.source() == Source.CACHE;
                if (o.problem() != null && !"TAG".equals(problem)) problem = o.problem();
            }
            if (missing) { failedTexts++; continue; }
            if (allCached) cachedItems++;
            byKey.put(p.key(), new Outcome(ko.toString(), allCached ? Source.CACHE : Source.DEEPL, problem));
        }
        return new Result(byKey, sentChars, cachedItems, failedTexts, lastError);
    }

    /** 원문을 요청 단위로 나눈다: 최대 BATCH개, 글자 합 MAX_CHARS 이하(DeepL 요청 크기 한도 128KiB 안쪽 — 한자는 UTF-8로 3바이트). */
    static List<List<String>> requests(List<String> srcs) {
        List<List<String>> out = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        int chars = 0;
        for (String s : srcs) {
            if (!cur.isEmpty() && (cur.size() >= BATCH || chars + s.length() > MAX_CHARS)) { out.add(cur); cur = new ArrayList<>(); chars = 0; }
            cur.add(s);
            chars += s.length();
        }
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }

    private record Sent(Map<String, String> out, int chars) {}

    private Sent send(List<String> srcs, DeepLTags.Mode mode, String lang, String glossaryId, List<String> instructions) {
        List<DeepLTags.Prepared> prepared = new ArrayList<>();
        List<String> xml = new ArrayList<>();
        int chars = 0;
        for (String s : srcs) { DeepLTags.Prepared p = DeepLTags.prepare(s, mode); prepared.add(p); xml.add(p.xml()); chars += p.xml().length(); }
        List<String> got = mt.translate(xml, lang, glossaryId, instructions);
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < srcs.size(); i++) out.put(srcs.get(i), DeepLTags.restore(got.get(i), prepared.get(i)));
        return new Sent(out, chars);
    }

    /** 깨끗한 쪽 → 태그가 맞는 쪽 → 남은 쪽 순서로 고른다. */
    private static Outcome pick(String src, String first, String second) {
        if (DeepLTags.clean(src, first)) return new Outcome(first, Source.DEEPL, null);
        if (DeepLTags.clean(src, second)) return new Outcome(second, Source.DEEPL, null);
        String ko = DeepLTags.tagsMatch(src, first) ? first : DeepLTags.tagsMatch(src, second) ? second : first != null ? first : second;
        if (ko == null) ko = "";
        return new Outcome(ko, Source.DEEPL, DeepLTags.tagsMatch(src, ko) ? "HAN" : "TAG");
    }
}
