package com.gametrans.translate;

import com.gametrans.config.AppProperties;
import com.gametrans.deepl.DeepLTranslator;
import com.gametrans.game.Game;
import com.gametrans.game.GameService;
import com.gametrans.glossary.EffectiveGlossary;
import com.gametrans.glossary.GlossaryEntry;
import com.gametrans.glossary.GlossaryPrompt;
import com.gametrans.llm.LlmClient;
import com.gametrans.llm.LlmClient.Reference;
import com.gametrans.llm.LlmClient.TranslateBatchRequest;
import com.gametrans.llm.LlmClient.TranslateRow;
import com.gametrans.llm.LlmClient.TranslationResult;
import com.gametrans.memory.MemoryService;
import com.gametrans.text.GlossaryCheck;
import com.gametrans.text.PlaceholderCheck;
import com.gametrans.translate.TranslateDtos.QuickTranslateRequest;
import com.gametrans.translate.TranslateDtos.QuickTranslateResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 파일 없이 문장 하나를 그 게임의 단어장·공용 용어집·번역 메모리에 맞춰 번역한다.
 * 작업·세그먼트·수정본을 만들지 않고, 결과를 메모리에 저장하지도 않는다(검수를 거치지 않은 문장이므로).
 * 검수 화면의 행 번역(RowTranslateService)도 GPT 호출은 이 클래스의 translateOne을 거친다.
 */
@Service
public class QuickTranslateService {
    static final int MAX_CHARS = 2000;
    private static final String INDEX = "1";

    private final GameService games;
    private final MemoryService memory;
    private final LlmClient llm;
    private final EffectiveGlossary effective;
    private final DeepLTranslator translator;
    private final AppProperties props;

    public QuickTranslateService(GameService games, MemoryService memory, LlmClient llm, EffectiveGlossary effective,
                                 DeepLTranslator translator, AppProperties props) {
        this.translator = translator;
        this.props = props;
        this.games = games;
        this.memory = memory;
        this.llm = llm;
        this.effective = effective;
    }

    public QuickTranslateResult translate(Long gameId, QuickTranslateRequest req) {
        Game game = games.get(gameId);
        String cn = req.text() == null ? "" : req.text().trim();
        if (cn.isEmpty()) throw new IllegalArgumentException("번역할 문장을 입력하세요.");
        if (cn.length() > MAX_CHARS) throw new IllegalArgumentException("한 번에 " + MAX_CHARS + "자까지 번역할 수 있습니다.");
        String en = req.en() == null || req.en().isBlank() ? null : req.en().trim();
        return translateOne(game, cn, en, req.basis(), req.instructions(), true);
    }

    /**
     * 문장 하나를 GPT로 번역한다. 용어집은 게임의 유효 단어장(SRC·ko 있음)
     * 가운데 이 원문에 실제로 걸리는 항목만 보낸다. 단어장의 메모 전용 항목은 원문에 find가 있을 때만 메모를 지시문 뒤에 붙인다.
     * useMemory가 true면 메모리에 원문이 완전히 같은 문장이 있고 지금 용어집과도 맞을 때 GPT 없이 그 문장을 돌려준다.
     */
    public QuickTranslateResult translateOne(Game game, String cn, String en, String basisParam, String instructions, boolean useMemory) {
        Terms terms = termsFor(game, cn);
        Map<String, String> glossary = terms.glossary();
        Map<String, String> withNotes = terms.withNotes();
        String fullInstructions = GlossaryPrompt.instructions(instructions, terms.notes());
        MemoryService.Lookup mem = memory.lookupGame(game.getName());

        // 파일 번역과 같은 규칙: 원문이 완전히 같고 지금 용어집과도 맞으면 GPT 없이 메모리 문장을 쓴다
        String remembered = useMemory ? mem.exact(cn) : null;
        if (remembered != null && PlaceholderCheck.matches(cn, remembered) && GlossaryCheck.missing(cn, remembered, glossary).isEmpty())
            return new QuickTranslateResult(remembered, "MEMORY", glossary, List.of(), List.of(), true, null);

        if (props.translate().deepl()) {
            // DeepL은 지시를 따르지 못한다 — instructions·단어장 메모는 쓰지 않는다. basis가 EN_FIRST이고 영어가 있을 때만 영어로 보낸다.
            String lang = "EN_FIRST".equals(basisParam) && en != null ? "EN" : "ZH";
            DeepLTranslator.Result r;
            try {
                r = translator.translate(game.getId(), List.of(new DeepLTranslator.Item(INDEX, cn, en)), lang);
            } catch (IllegalStateException e) {
                throw new IllegalArgumentException(e.getMessage());   // 키 없음 → 400
            } catch (com.gametrans.deepl.MtException e) {
                throw new IllegalStateException(e.getMessage());      // 단어장 만들기 실패 등 → 409와 그 이유
            }
            DeepLTranslator.Outcome o = r.byKey().get(INDEX);
            if (o == null || o.ko().isBlank())
                throw new IllegalStateException(r.lastError() != null ? r.lastError() : "DeepL이 번역을 돌려주지 않았습니다. 다시 시도하세요.");
            return new QuickTranslateResult(o.ko(), o.source().name(), glossary, List.of(), GlossaryCheck.missing(cn, o.ko(), glossary),
                    PlaceholderCheck.matches(cn, o.ko()), o.problem());
        }
        List<Reference> refs = mem.references(List.of(cn));
        String basis = "CN_ONLY".equals(basisParam) ? "CN_ONLY" : "EN_FIRST";
        String ko = null;
        for (TranslationResult r : llm.translateBatch(new TranslateBatchRequest(basis, fullInstructions, withNotes, List.of(new TranslateRow(INDEX, cn, en)), refs)))
            if (INDEX.equals(r.index()) && r.ko() != null && !r.ko().isBlank()) ko = r.ko().trim();
        if (ko == null) throw new IllegalStateException("GPT가 번역을 돌려주지 않았습니다. 다시 시도하세요.");
        return new QuickTranslateResult(ko, "LLM", glossary, refs, GlossaryCheck.missing(cn, ko, glossary), PlaceholderCheck.matches(cn, ko), null);
    }

    /** 여러 행 번역의 한 행 결과. problem: DeepL 태그 어긋남("TAG")·중국어 남음("HAN"), 없으면 null. */
    public record Outcome(String ko, String problem) {}

    /**
     * 여러 행을 한 번에 번역한다(2026-11-13 여러 행 다시 번역). translate.engine 설정과 무관하게 engine을 따른다.
     * DEEPL: translator.translate 한 번, instructions는 쓰지 않는다. basis가 EN_FIRST면 영어가 있는 행만 영어로 보낸다.
     * GPT: llm.translateBatch 한 번 — instructions 뒤에 행들에 걸리는 단어장 메모를 붙이고, 용어집은 행들에 걸리는 것을 합친다.
     * 메모리는 읽지도 쓰지도 않는다. 결과가 비었거나 없는 행은 맵에 넣지 않는다(키 = Item.key).
     */
    public Map<String, Outcome> translateMany(Game game, List<DeepLTranslator.Item> items, String basis, TranslateDtos.Engine engine, String instructions) {
        Map<String, Outcome> out = new LinkedHashMap<>();
        if (items.isEmpty()) return out;
        if (engine == TranslateDtos.Engine.DEEPL) {
            DeepLTranslator.Result r;
            try {
                r = translator.translate(game.getId(), items, "EN_FIRST".equals(basis) ? "EN" : "ZH");
            } catch (IllegalStateException e) {
                throw new IllegalArgumentException(e.getMessage());   // 키 없음 → 400
            } catch (com.gametrans.deepl.MtException e) {
                throw new IllegalStateException(e.getMessage());      // 단어장 만들기 실패 등 → 409와 그 이유
            }
            for (DeepLTranslator.Item it : items) {
                DeepLTranslator.Outcome o = r.byKey().get(it.key());
                if (o != null && o.ko() != null && !o.ko().isBlank()) out.put(it.key(), new Outcome(o.ko(), o.problem()));
            }
            return out;
        }
        List<GlossaryEntry> entries = effective.of(game.getId());
        Map<String, String> withNotes = new LinkedHashMap<>();
        java.util.Set<String> notes = new java.util.LinkedHashSet<>();
        List<TranslateRow> rows = new java.util.ArrayList<>();
        for (DeepLTranslator.Item it : items) {
            Terms t = termsFor(entries, it.cn());
            t.withNotes().forEach(withNotes::putIfAbsent);
            notes.addAll(t.notes());
            rows.add(new TranslateRow(it.key(), it.cn(), it.en()));
        }
        String fullInstructions = GlossaryPrompt.instructions(instructions, List.copyOf(notes));
        String b = "CN_ONLY".equals(basis) ? "CN_ONLY" : "EN_FIRST";
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (DeepLTranslator.Item it : items) keys.add(it.key());
        for (TranslationResult r : llm.translateBatch(new TranslateBatchRequest(b, fullInstructions, withNotes, rows, List.of())))
            if (keys.contains(r.index()) && r.ko() != null && !r.ko().isBlank()) out.putIfAbsent(r.index(), new Outcome(r.ko().trim(), null));
        return out;
    }

    /** glossary: 이 원문에 걸리는 용어(메모 뗀 번역어). withNotes: 같은 항목을 메모째(GPT용). notes: 원문에 find가 있는 단어장 메모 전용 항목의 메모. */
    public record Terms(Map<String, String> glossary, Map<String, String> withNotes, List<String> notes) {}

    /**
     * 게임의 유효 단어장(SRC·ko 있음) 가운데 이 원문에 실제로 걸리는 항목. 행 줄이기(RowShortenService)도 같은 용어를 쓴다.
     */
    public Terms termsFor(Game game, String cn) {
        return termsFor(effective.of(game.getId()), cn);
    }

    /** 단어장 항목을 미리 읽어 둔 쪽(여러 행 다듬기)도 쓴다. */
    static Terms termsFor(List<GlossaryEntry> entries, String cn) {
        Map<String, String> merged = GlossaryPrompt.srcTerms(entries);
        // 겹치는 용어는 긴 쪽만 보낸다. 짧은 쪽까지 보내면 GPT가 서로 어긋나는 두 번역을 받는다
        Map<String, String> glossary = GlossaryCheck.applicable(cn, merged);
        // GPT에는 같은 항목을 메모째 보낸다(applicable은 메모를 뗀 번역어를 돌려준다). 화면에 돌려주는 용어집은 메모 없는 쪽.
        Map<String, String> withNotes = new LinkedHashMap<>();
        for (String term : glossary.keySet()) withNotes.put(term, merged.get(term).trim());
        return new Terms(glossary, withNotes, GlossaryPrompt.memoNotes(entries, cn));
    }
}
