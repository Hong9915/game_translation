package com.gametrans.glossary;

import com.gametrans.common.NotFoundException;
import com.gametrans.game.GameRepository;
import com.gametrans.llm.LlmClient;
import com.gametrans.llm.LlmClient.PhraseInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 표기 갈림 구절의 분류(V22). 저장된 분류를 먼저 쓰고, 처음 보는 구절만 가벼운 GPT 모델에 BATCH개씩 동시에 물어 저장한다 —
 * 같은 구절은 게임마다 한 번만 묻는다. 보기용이라 GPT가 실패하면 그 묶음은
 * 분류 없이 돌려주고, 다음 요청에서 다시 묻는다.
 */
@Service
public class GlossaryCategoryService {
    private static final Logger log = LoggerFactory.getLogger(GlossaryCategoryService.class);

    static final int BATCH = 100;
    /** 한 요청에 물을 수 있는 구절 수. 게임 하나의 표기 갈림 카드 수(수백 개)를 넉넉히 넘는다. */
    static final int MAX_ITEMS = 2000;
    static final Set<String> CATEGORIES = Set.of(
            "CHARACTER", "ITEM", "SKILL", "PLACE", "NAME_OTHER", "STAT", "NOUN", "VERB", "COLOR", "MODIFIER", "FUNCTION", "PHRASE");

    private final GlossaryCategoryRepository repo;
    private final GameRepository games;
    private final LlmClient llm;
    private final int concurrency;

    public GlossaryCategoryService(GlossaryCategoryRepository repo, GameRepository games, LlmClient llm,
                                   @Value("${app.llm-concurrency:4}") int concurrency) {
        this.concurrency = Math.max(1, concurrency);
        this.repo = repo;
        this.games = games;
        this.llm = llm;
    }

    public record Item(String src, String ko, String example) {}

    /** 원문 → 분류. 분류를 못 받은 구절은 결과에 없다. */
    public Map<String, String> categorize(Long gameId, List<Item> items) {
        if (!games.existsById(gameId)) throw new NotFoundException("게임을 찾을 수 없습니다: " + gameId);
        if (items.size() > MAX_ITEMS) throw new IllegalArgumentException("한 번에 " + MAX_ITEMS + "개까지 물을 수 있습니다.");
        Map<String, Item> wanted = new LinkedHashMap<>();
        for (Item i : items) if (i.src() != null && !i.src().isBlank()) wanted.putIfAbsent(i.src(), i);
        Map<String, String> out = new LinkedHashMap<>();
        if (wanted.isEmpty()) return out;
        for (GlossaryCategory c : repo.findByGameIdAndSrcIn(gameId, wanted.keySet())) out.put(c.getSrc(), c.getCategory());

        List<Item> missing = wanted.values().stream().filter(i -> !out.containsKey(i.src())).toList();
        if (missing.isEmpty()) return out;
        // 묶음들을 동시에 묻는다(용어 854개인 게임: 차례로 9번이면 100초). 실패한 묶음은 빈 결과라 다른 묶음을 막지 않는다.
        List<List<Item>> batches = new ArrayList<>();
        for (int i = 0; i < missing.size(); i += BATCH) batches.add(missing.subList(i, Math.min(i + BATCH, missing.size())));
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, batches.size()));
        List<Map<String, String>> results;
        try {
            List<CompletableFuture<Map<String, String>>> futures = batches.stream()
                    .map(b -> CompletableFuture.supplyAsync(() -> classify(b), pool)).toList();
            results = futures.stream().map(CompletableFuture::join).toList();
        } finally {
            pool.shutdown();
        }
        for (Map<String, String> got : results) {
            if (got.isEmpty()) continue;
            try {
                repo.saveAll(got.entrySet().stream().map(e -> new GlossaryCategory(gameId, e.getKey(), e.getValue())).toList());
            } catch (DataAccessException e) {
                // 두 요청이 같은 구절을 동시에 저장한 경우 — 이번 결과는 그대로 돌려주고 저장은 먼저 한 쪽을 둔다
                log.warn("구절 분류를 저장하지 못했다({}개): {}", got.size(), e.getMessage());
            }
            out.putAll(got);
        }
        return out;
    }

    private Map<String, String> classify(List<Item> batch) {
        List<PhraseInput> in = new ArrayList<>();
        for (Item i : batch) in.add(new PhraseInput(i.src(), i.ko(), i.example(), null));
        Map<String, String> got = new LinkedHashMap<>();
        Set<String> asked = batch.stream().map(Item::src).collect(Collectors.toSet());
        try {
            for (LlmClient.PhraseCategory c : llm.classifyPhrases(in)) {
                String cat = c.category() == null ? "" : c.category().strip().toUpperCase();
                if (asked.contains(c.src()) && CATEGORIES.contains(cat)) got.putIfAbsent(c.src(), cat);
            }
        } catch (RuntimeException e) {
            log.warn("구절 분류 GPT 호출 실패({}개): {}", batch.size(), e.getMessage());
        }
        return got;
    }
}
