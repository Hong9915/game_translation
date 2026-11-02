package com.gametrans;

import com.gametrans.llm.LlmClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 테스트용 결정적 LLM. 필드를 채워 응답을 제어한다. */
public class FakeLlmClient implements LlmClient {
    public Map<String, String> translations = new HashMap<>();
    /** 이 인덱스는 첫 호출에서만 응답에서 빠진다(누락 재시도 검증용). */
    public Set<String> dropOnce = new HashSet<>();
    /** 이 인덱스는 첫 호출에서 자리표시자를 망가뜨린 번역을 돌려준다. */
    public Set<String> breakPlaceholdersOnce = new HashSet<>();
    public List<TranslateBatchRequest> translateCalls = new ArrayList<>();
    public boolean pingResult = true;
    @Override public List<TranslationResult> translateBatch(TranslateBatchRequest request) {
        translateCalls.add(request);
        List<TranslationResult> out = new ArrayList<>();
        for (TranslateRow r : request.rows()) {
            if (dropOnce.remove(r.index())) continue;
            String ko = translations.getOrDefault(r.cn(), "KO:" + r.cn());
            if (breakPlaceholdersOnce.remove(r.index())) ko = ko.replaceAll("\\{\\d+\\}", "");
            out.add(new TranslationResult(r.index(), ko));
        }
        return out;
    }

    /** 마지막 번역 요청에 실린 용어집(원문 → 한국어). 요청이 없었으면 빈 맵. */
    public Map<String, String> lastGlossary() {
        return translateCalls.isEmpty() ? Map.of() : translateCalls.get(translateCalls.size() - 1).glossary();
    }

    /** 마지막 번역 요청의 추가 지시문. 요청이 없었으면 null. */
    public String lastInstructions() {
        return translateCalls.isEmpty() ? null : translateCalls.get(translateCalls.size() - 1).instructions();
    }

    @Override public List<TranslationResult> shortenBatch(List<ShortenRow> rows) {
        List<TranslationResult> out = new ArrayList<>();
        for (ShortenRow r : rows) {
            String ko = r.ko().length() > r.maxChars() ? r.ko().substring(0, r.maxChars()) : r.ko();
            out.add(new TranslationResult(r.index(), ko));
        }
        return out;
    }

    /** 기존 한국어 → 고친 문장. 여기 없는 행은 KEEP으로 답한다. */
    public Map<String, String> proofFixes = new HashMap<>();
    public List<ProofreadBatchRequest> proofreadCalls = new ArrayList<>();

    @Override public List<ProofreadResult> proofreadBatch(ProofreadBatchRequest request) {
        proofreadCalls.add(request);
        List<ProofreadResult> out = new ArrayList<>();
        for (ProofreadRow r : request.rows()) {
            if (dropOnce.remove(r.index())) continue;
            String fix = proofFixes.get(r.ko());
            out.add(fix == null ? new ProofreadResult(r.index(), "KEEP", null, null)
                    : new ProofreadResult(r.index(), "FIX", fix, "MISTRANSLATION: 테스트"));
        }
        return out;
    }

    /** 용어 고치기: 빠진 용어의 원문 자리를 확정 번역으로 바꾼 듯 흉내 낸다 — ko 앞에 확정 번역들을 붙인다. fixTermsNoop이면 그대로 돌려준다. */
    public List<List<FixTermsRow>> fixTermsCalls = new ArrayList<>();
    public boolean fixTermsNoop;

    @Override public List<TranslationResult> fixTermsBatch(List<FixTermsRow> rows) {
        fixTermsCalls.add(rows);
        List<TranslationResult> out = new ArrayList<>();
        for (FixTermsRow r : rows) {
            if (dropOnce.remove(r.index())) continue;
            String need = String.join(" ", r.missing().values().stream().map(com.gametrans.text.GlossaryCheck::koOf).toList());
            out.add(new TranslationResult(r.index(), fixTermsNoop ? r.ko() : need + " " + r.ko()));
        }
        return out;
    }

    /** 용어 바꾸기: termSwap에 든 지금 번역 → 고친 글. 없는 번역은 그대로 돌려준다(GPT가 못 고친 것). 호출은 termSwapCalls에 쌓인다. */
    public Map<String, String> termSwap = new HashMap<>();
    public List<List<FixTermsRow>> termSwapCalls = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override public List<TranslationResult> termSwapBatch(List<FixTermsRow> rows) {
        termSwapCalls.add(rows);
        List<TranslationResult> out = new ArrayList<>();
        for (FixTermsRow r : rows) out.add(new TranslationResult(r.index(), termSwap.getOrDefault(r.ko(), r.ko())));
        return out;
    }

    /** shortenOptions가 돌려줄 후보(그대로). 호출은 shortenCalls에 쌓인다. */
    public List<String> shortenOptions = List.of();
    public List<ShortenOptionsRequest> shortenCalls = new ArrayList<>();

    @Override public List<String> shortenOptions(ShortenOptionsRequest request) {
        shortenCalls.add(request);
        return new ArrayList<>(shortenOptions);
    }

    @Override public boolean ping() { return pingResult; }

    /** 구절 분류 응답(원문 → 분류). 없는 원문은 응답에서 빠진다. */
    public Map<String, String> categories = new HashMap<>();
    /** 분류 묶음은 동시에 불리므로 동기화된 목록에 모은다. */
    public List<List<PhraseInput>> classifyCalls = java.util.Collections.synchronizedList(new ArrayList<>());

    @Override public List<PhraseCategory> classifyPhrases(List<PhraseInput> phrases) {
        classifyCalls.add(phrases);
        List<PhraseCategory> out = new ArrayList<>();
        for (PhraseInput p : phrases) if (categories.containsKey(p.src())) out.add(new PhraseCategory(p.src(), categories.get(p.src())));
        return out;
    }

    /** 단어장 요청 초안 응답(원문 → 한국어). 없는 원문은 응답에서 빠진다. 호출은 draftCalls에 쌓인다(동시에 불릴 수 있다). */
    public Map<String, String> termDrafts = new java.util.concurrent.ConcurrentHashMap<>();
    public List<TermDraftRequest> draftCalls = java.util.Collections.synchronizedList(new ArrayList<>());

    @Override public List<TermDraft> draftTerms(TermDraftRequest req) {
        draftCalls.add(req);
        List<TermDraft> out = new ArrayList<>();
        for (String t : req.terms()) if (termDrafts.containsKey(t)) out.add(new TermDraft(t, termDrafts.get(t), "가짜"));
        return out;
    }

    /** tool calling 대본: 호출마다 앞에서 하나씩 꺼낸다. 비면 글 "끝"만 돌려준다. 받은 messages는 toolMessages에 남는다. */
    public java.util.Deque<AssistantTurn> toolTurns = new java.util.ArrayDeque<>();
    public List<List<Map<String, Object>>> toolMessages = new ArrayList<>();
    public List<List<ToolSpec>> toolSpecsSeen = new ArrayList<>();

    @Override public AssistantTurn chatTools(String stage, List<Map<String, Object>> messages, List<ToolSpec> tools) {
        toolMessages.add(List.copyOf(messages));
        toolSpecsSeen.add(List.copyOf(tools));
        return toolTurns.isEmpty() ? new AssistantTurn("끝", List.of()) : toolTurns.poll();
    }
}
