package com.gametrans.glossary;

import com.gametrans.common.NotFoundException;
import com.gametrans.game.GameRepository;
import com.gametrans.glossary.GlossaryDtos.Rendering;
import com.gametrans.glossary.GlossaryEntry.Kind;
import com.gametrans.glossary.TermRequestDtos.ApproveItem;
import com.gametrans.glossary.TermRequestDtos.ApproveRequest;
import com.gametrans.glossary.TermRequestDtos.ApproveResult;
import com.gametrans.glossary.TermRequestDtos.ItemDto;
import com.gametrans.glossary.TermRequestDtos.RequestDto;
import com.gametrans.glossary.TermRequestDtos.RequestSummary;
import com.gametrans.glossary.TermRequestForm.FormTerm;
import com.gametrans.game.DeepLInstructions;
import com.gametrans.llm.LlmClient;
import com.gametrans.llm.LlmClient.Reference;
import com.gametrans.llm.LlmClient.TermDraft;
import com.gametrans.llm.LlmClient.TermDraftRequest;
import com.gametrans.project.ProjectService;
import com.gametrans.storage.FileStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 단어장 요청(설계 2026-10-30). 개발사가 채운 요청양식을 읽어 용어마다 초안을 만든다: 번역본에 이미 옮겨 둔 표기가 있으면
 * 그 표기들(CORPUS, READY — 갈려 있으면 주 표기와 다른 표기 모두), 없거나 원문이 1글자면 GPT 초안을 기다린다(GPT, PENDING).
 * 1글자는 다른 낱말 속에 너무 흔해 대조하지 않는다.
 * approve·generate에는 트랜잭션을 걸지 말 것 — adopt가 안에서 em.clear()를 하고, GPT를 기다리는 동안 DB 연결을 잡으면 안 된다.
 */
@Service
public class TermRequestService {
    private static final Logger log = LoggerFactory.getLogger(TermRequestService.class);

    /** GPT 한 번에 묻는 용어 수(같은 분류끼리). */
    static final int DRAFT_BATCH = 30;
    /** GPT에 참고로 싣는 이미 정한 표기 수. */
    static final int MAX_KNOWN = 50;

    private final TermRequestRepository requests;
    private final TermRequestItemRepository items;
    private final GameRepository games;
    private final GlossaryService glossary;
    private final GlossaryEntryRepository entries;
    private final GlossaryCategoryRepository categories;
    private final FileStorage storage;
    private final LlmClient llm;
    private final TermFinder finder;
    private final int concurrency;

    public TermRequestService(TermRequestRepository requests, TermRequestItemRepository items, GameRepository games,
                              GlossaryService glossary, GlossaryEntryRepository entries, GlossaryCategoryRepository categories,
                              FileStorage storage, LlmClient llm, TermFinder finder, @Value("${app.llm-concurrency:4}") int concurrency) {
        this.llm = llm;
        this.finder = finder;
        this.concurrency = Math.max(1, concurrency);
        this.requests = requests;
        this.items = items;
        this.games = games;
        this.glossary = glossary;
        this.entries = entries;
        this.categories = categories;
        this.storage = storage;
    }

    /** 게임·양식을 먼저 확인하고(실패하면 아무것도 남기지 않는다) 요청·항목을 저장한 뒤 원본 파일을 둔다. */
    @Transactional
    public RequestDto create(Long gameId, MultipartFile file, Long reviewerId) {
        if (!games.existsById(gameId)) throw new NotFoundException("게임을 찾을 수 없습니다: " + gameId);
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (IOException e) { throw new UncheckedIOException(e); }
        List<FormTerm> terms = TermRequestForm.parse(bytes);
        if (terms.isEmpty()) throw new IllegalArgumentException("양식에 용어가 없습니다.");
        String name = ProjectService.sanitizeFileName(file.getOriginalFilename() == null ? "upload.xlsx" : file.getOriginalFilename());

        TermRequest req = new TermRequest();
        req.setGameId(gameId);
        req.setFileName(name);
        req.setStorageKey("");   // id가 나와야 경로를 정한다
        req.setCreatedBy(reviewerId);
        req = requests.saveAndFlush(req);
        String key = "glossary-requests/" + gameId + "/" + req.getId() + "/" + name;
        req.setStorageKey(key);

        // 업로드 한 번에 하나씩: 갈림 판정용 쌍 색인, 그게 못 찾을 때 쓰는 기존 표기 후보용 색인
        List<GlossarySuggester.Row> rows = glossary.rowsOf(gameId);
        GlossaryVariants.Pairs pairs = GlossaryVariants.pairs(rows);
        GlossarySuggester.Corpus corpus = GlossarySuggester.corpus(rows);
        List<TermRequestItem> made = new ArrayList<>();
        for (int i = 0; i < terms.size(); i++) made.add(draft(req.getId(), i, terms.get(i), pairs, corpus));
        items.saveAll(made);
        storage.put(key, bytes);
        return dto(req, made);
    }

    /**
     * 번역본 속 표기: 먼저 표기 갈림 탭과 같은 판정(주 표기 + 다른 표기), 갈림이 없으면 기존 표기 후보(suggest).
     * 원문 1글자는 대조하지 않는다. 둘 다 없으면 GPT 초안을 기다린다.
     */
    private static TermRequestItem draft(Long requestId, int seq, FormTerm term, GlossaryVariants.Pairs pairs, GlossarySuggester.Corpus corpus) {
        TermRequestItem it = new TermRequestItem();
        it.setRequestId(requestId);
        it.setSeq(seq);
        it.setCategory(term.category());
        it.setSrc(term.src());
        List<Rendering> renderings = List.of();
        if (term.src().codePointCount(0, term.src().length()) >= 2) {
            renderings = GlossaryVariants.renderings(pairs, term.src());
            if (renderings.isEmpty()) renderings = corpus.suggest(term.src(), null).candidates().stream()
                    .map(c -> new Rendering(c.oldKo(), c.rows(), GlossaryRecommender.clip(c.example().src()), GlossaryRecommender.clip(c.example().before())))
                    .toList();
        }
        it.setRenderings(new ArrayList<>(renderings));
        it.setSource(renderings.isEmpty() ? TermRequestItem.GPT : TermRequestItem.CORPUS);
        it.setStatus(renderings.isEmpty() ? TermRequestItem.PENDING : TermRequestItem.READY);
        return it;
    }

    /**
     * 항목마다 adopt 한 번: replace의 표기를 ko로 고치고 단어장에 넣는다(같은 원문은 덮어씀). renderings 표기 중 ko·replace에
     * 없는 것은 남기는 표기(keep)라 메모 항목이 된다. 그 뒤 항목을 APPROVED로, 양식 분류를 glossary_category에 둔다(덮어씀).
     * 트랜잭션을 걸지 않는다 — adopt가 항목마다 자기 트랜잭션이고 안에서 em.clear()를 하므로, 항목은 adopt 뒤에 다시 읽어 저장한다.
     * 한 항목이라도 이 요청 것이 아니거나 표기가 비면 아무것도 승인하기 전에 거절한다.
     */
    public ApproveResult approve(Long requestId, ApproveRequest req, Long reviewerId) {
        TermRequest request = requests.findById(requestId).orElseThrow(() -> new NotFoundException("단어장 요청을 찾을 수 없습니다: " + requestId));
        List<ApproveItem> wanted = req == null || req.items() == null ? List.of() : req.items();
        if (wanted.isEmpty()) throw new IllegalArgumentException("승인할 항목이 없습니다.");
        Map<Long, TermRequestItem> mine = items.findByRequestIdOrderBySeq(requestId).stream()
                .collect(Collectors.toMap(TermRequestItem::getId, Function.identity()));
        for (ApproveItem a : wanted) {
            if (!mine.containsKey(a.itemId())) throw new IllegalArgumentException("이 요청의 항목이 아닙니다: " + a.itemId());
            if (a.ko() == null || a.ko().isBlank()) throw new IllegalArgumentException("최종 표기를 입력하세요.");
        }
        Long gameId = request.getGameId();
        int applied = 0;
        for (ApproveItem a : wanted) {
            TermRequestItem it = mine.get(a.itemId());
            String ko = a.ko().trim();
            List<String> replace = a.replace() == null ? List.of() : a.replace().stream().filter(r -> r != null).map(String::trim).toList();
            List<String> keep = it.getRenderings().stream().map(Rendering::ko)
                    .filter(k -> k != null && !k.equals(ko) && !replace.contains(k)).toList();
            applied += glossary.adopt(gameId, it.getSrc(), ko, replace, keep, reviewerId).applied();

            TermRequestItem fresh = items.findById(it.getId()).orElseThrow();
            fresh.setStatus(TermRequestItem.APPROVED);
            fresh.setFinalKo(ko);
            fresh.setApprovedBy(reviewerId);
            fresh.setApprovedAt(Instant.now());
            items.save(fresh);
            categories.save(new GlossaryCategory(gameId, it.getSrc(), TermRequestForm.glossaryCategory(it.getCategory())));
        }
        return new ApproveResult(wanted.size(), applied, get(requestId));
    }

    /**
     * PENDING·FAILED 항목의 GPT 초안을 만든다. 같은 분류끼리 DRAFT_BATCH개씩 묶어 app.llm-concurrency만큼 동시에 묻는다
     * (GlossaryCategoryService와 같은 방식). 참고로 이 요청에서 승인한 표기와 번역본에서 찾은 표기(READY CORPUS의 첫 표기),
     * 게임 지시문을 싣는다. 답이 온 항목은 READY, 답이 없거나 그 묶음이 실패하면 FAILED — 다시 부르면 다시 묻는다.
     * 트랜잭션을 걸지 않는다: GPT를 기다리는 동안 DB 연결을 잡지 않고, 저장 직전에 항목을 다시 읽어 그새 승인된 항목은 두고 간다.
     */
    public RequestDto generate(Long requestId) {
        TermRequest request = requests.findById(requestId).orElseThrow(() -> new NotFoundException("단어장 요청을 찾을 수 없습니다: " + requestId));
        List<TermRequestItem> all = items.findByRequestIdOrderBySeq(requestId);
        List<TermRequestItem> wanted = all.stream().filter(TermRequestService::needsDraft).toList();
        if (wanted.isEmpty()) return get(requestId);

        List<Reference> known = new ArrayList<>();
        for (TermRequestItem i : all)
            if (TermRequestItem.APPROVED.equals(i.getStatus()) && i.getFinalKo() != null) known.add(new Reference(i.getSrc(), i.getFinalKo()));
        for (TermRequestItem i : all)
            if (TermRequestItem.READY.equals(i.getStatus()) && TermRequestItem.CORPUS.equals(i.getSource()) && !i.getRenderings().isEmpty())
                known.add(new Reference(i.getSrc(), i.getRenderings().get(0).ko()));
        if (known.size() > MAX_KNOWN) known = known.subList(0, MAX_KNOWN);
        List<String> instructions = games.findById(request.getGameId())
                .map(g -> DeepLInstructions.lines(g.getDeeplInstructions())).orElse(List.of());

        Map<String, List<String>> byCategory = new LinkedHashMap<>();
        for (TermRequestItem i : wanted) {
            List<String> terms = byCategory.computeIfAbsent(i.getCategory(), k -> new ArrayList<>());
            if (!terms.contains(i.getSrc())) terms.add(i.getSrc());
        }
        List<TermDraftRequest> batches = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byCategory.entrySet())
            for (int i = 0; i < e.getValue().size(); i += DRAFT_BATCH)
                batches.add(new TermDraftRequest(e.getKey(), List.copyOf(e.getValue().subList(i, Math.min(i + DRAFT_BATCH, e.getValue().size()))),
                        List.copyOf(known), instructions));
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, batches.size()));
        List<Map<String, TermDraft>> results;
        try {
            List<CompletableFuture<Map<String, TermDraft>>> futures = batches.stream()
                    .map(b -> CompletableFuture.supplyAsync(() -> draftBatch(b), pool)).toList();
            results = futures.stream().map(CompletableFuture::join).toList();
        } finally {
            pool.shutdown();
        }
        // 분류 + 원문 → 초안(같은 원문이 다른 분류로 두 번 올라와도 섞이지 않게)
        Map<String, TermDraft> got = new HashMap<>();
        for (int b = 0; b < batches.size(); b++)
            for (Map.Entry<String, TermDraft> e : results.get(b).entrySet()) got.put(batches.get(b).category() + "\u0000" + e.getKey(), e.getValue());

        List<TermRequestItem> fresh = items.findAllById(wanted.stream().map(TermRequestItem::getId).toList()).stream()
                .filter(TermRequestService::needsDraft).toList();
        for (TermRequestItem i : fresh) {
            TermDraft d = got.get(i.getCategory() + "\u0000" + i.getSrc());
            if (d == null) {
                i.setStatus(TermRequestItem.FAILED);
            } else {
                i.setGptKo(d.ko().strip());
                i.setGptReason(d.reason() == null || d.reason().isBlank() ? null : d.reason().strip());
                i.setStatus(TermRequestItem.READY);
            }
        }
        items.saveAll(fresh);
        return get(requestId);
    }

    /**
     * GPT로 표기 확인(설계 2026-11-02 §4). 승인 안 됐고 원문 2글자 이상이며 아직 확인 안 한 항목마다 TermFinder로 번역본 속 표기를
     * 다시 찾아 renderings를 갈아 끼운다(통계 결과는 버림). 실패한 항목은 그대로 두고 확인 표시도 안 남겨 다시 부르면 다시 묻는다.
     * 번역본에 없다고 나온 항목은 초안이 없으면 PENDING이 되고, 끝에 generate로 초안까지 만든다.
     * generate와 같은 이유로 트랜잭션을 걸지 않고, 저장 직전에 다시 읽어 그새 승인된 항목은 두고 간다.
     */
    public RequestDto find(Long requestId) {
        TermRequest request = requests.findById(requestId).orElseThrow(() -> new NotFoundException("단어장 요청을 찾을 수 없습니다: " + requestId));
        List<TermRequestItem> wanted = items.findByRequestIdOrderBySeq(requestId).stream().filter(TermRequestService::needsFind).toList();
        if (wanted.isEmpty()) return get(requestId);

        GlossaryVariants.Pairs pairs = GlossaryVariants.pairs(glossary.rowsOf(request.getGameId()));
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, wanted.size()));
        Map<Long, TermFinder.Found> found = new HashMap<>();
        try {
            List<CompletableFuture<TermFinder.Found>> futures = wanted.stream()
                    .map(i -> CompletableFuture.supplyAsync(() -> finder.find(i.getSrc(), i.getCategory(), pairs), pool)).toList();
            for (int k = 0; k < wanted.size(); k++) {
                TermFinder.Found f = futures.get(k).join();
                if (f != null) found.put(wanted.get(k).getId(), f);
            }
        } finally {
            pool.shutdown();
        }

        List<TermRequestItem> fresh = items.findAllById(found.keySet()).stream().filter(TermRequestService::needsFind).toList();
        boolean pending = false;
        for (TermRequestItem i : fresh) {
            TermFinder.Found f = found.get(i.getId());
            i.setGptChecked(true);
            i.setGptFindReason(f.reason());
            i.setRenderings(new ArrayList<>(f.renderings()));
            if (!f.renderings().isEmpty()) {
                i.setSource(TermRequestItem.CORPUS);
                i.setStatus(TermRequestItem.READY);
            } else {
                i.setSource(TermRequestItem.GPT);
                i.setStatus(i.getGptKo() == null ? TermRequestItem.PENDING : TermRequestItem.READY);
                pending |= i.getGptKo() == null;
            }
        }
        items.saveAll(fresh);
        return pending ? generate(requestId) : get(requestId);
    }

    private static boolean needsFind(TermRequestItem i) {
        return !TermRequestItem.APPROVED.equals(i.getStatus()) && !i.isGptChecked() && i.getSrc().codePointCount(0, i.getSrc().length()) >= 2;
    }

    private static boolean needsDraft(TermRequestItem i) {
        return TermRequestItem.PENDING.equals(i.getStatus()) || TermRequestItem.FAILED.equals(i.getStatus());
    }

    /** 원문 → 초안. 묻지 않은 원문·빈 한국어는 버린다. GPT가 실패하면 빈 결과(그 묶음은 FAILED가 된다). */
    private Map<String, TermDraft> draftBatch(TermDraftRequest req) {
        Map<String, TermDraft> got = new LinkedHashMap<>();
        Set<String> asked = Set.copyOf(req.terms());
        try {
            for (TermDraft d : llm.draftTerms(req))
                if (d != null && asked.contains(d.src()) && d.ko() != null && !d.ko().isBlank()) got.putIfAbsent(d.src(), d);
        } catch (RuntimeException e) {
            log.warn("단어장 요청 GPT 초안 실패({} {}개): {}", req.category(), req.terms().size(), e.getMessage());
        }
        return got;
    }

    @Transactional(readOnly = true)
    public RequestDto get(Long id) {
        TermRequest req = requests.findById(id).orElseThrow(() -> new NotFoundException("단어장 요청을 찾을 수 없습니다: " + id));
        return dto(req, items.findByRequestIdOrderBySeq(id));
    }

    /** 이 게임의 요청, 최신 먼저. */
    @Transactional(readOnly = true)
    public List<RequestSummary> list(Long gameId) {
        if (!games.existsById(gameId)) throw new NotFoundException("게임을 찾을 수 없습니다: " + gameId);
        List<TermRequest> rs = requests.findByGameIdOrderByCreatedAtDescIdDesc(gameId);
        if (rs.isEmpty()) return List.of();
        Map<Long, List<TermRequestItem>> byRequest = items.findByRequestIdIn(rs.stream().map(TermRequest::getId).toList()).stream()
                .collect(Collectors.groupingBy(TermRequestItem::getRequestId));
        return rs.stream().map(r -> {
            List<TermRequestItem> its = byRequest.getOrDefault(r.getId(), List.of());
            return new RequestSummary(r.getId(), r.getFileName(), r.getCreatedAt(), its.size(),
                    count(its, TermRequestItem.APPROVED), count(its, TermRequestItem.PENDING));
        }).toList();
    }

    private static int count(List<TermRequestItem> its, String status) {
        return (int) its.stream().filter(i -> status.equals(i.getStatus())).count();
    }

    /** existing*: 지금 게임 단어장의 같은 원문(SRC) 항목. 게임 단어장을 한 번 읽어 맞춘다. */
    private RequestDto dto(TermRequest req, List<TermRequestItem> its) {
        Map<String, GlossaryEntry> existing = entries.findByGameId(req.getGameId()).stream()
                .filter(e -> e.getKind() == Kind.SRC)
                .collect(Collectors.toMap(GlossaryEntry::getFind, Function.identity(), (a, b) -> a));
        List<ItemDto> out = its.stream().map(i -> {
            GlossaryEntry e = existing.get(i.getSrc());
            return new ItemDto(i.getId(), i.getSeq(), i.getCategory(), i.getSrc(), i.getRenderings(), i.getSource(), i.getGptKo(),
                    i.getGptReason(), i.getStatus(), i.getFinalKo(), e == null ? null : e.getKo(), e == null ? null : e.getNote(),
                    i.isGptChecked(), i.getGptFindReason());
        }).toList();
        return new RequestDto(req.getId(), req.getGameId(), req.getFileName(), req.getCreatedAt(), out);
    }
}
