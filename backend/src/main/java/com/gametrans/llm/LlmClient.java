package com.gametrans.llm;

import java.util.List;
import java.util.Map;

public interface LlmClient {
    /** frequentPhrases: 파일 전체에서 반복되는 명사구(구절 → 등장 문장 수). 이 묶음에 나오는 것만 힌트로 넘긴다. */
    List<TermExtraction> extractTerms(List<String> sourceTexts, Map<String, Integer> frequentPhrases);
    default List<CandidateSuggestion> suggestCandidates(List<TermInput> terms) { return suggestCandidates(terms, java.util.Map.of()); }
    /** fixedParts: 이미 번역이 정해진 짧은 용어(원문 → 한국어). 이를 품은 긴 용어의 후보는 그 번역을 그대로 써야 한다. */
    List<CandidateSuggestion> suggestCandidates(List<TermInput> terms, java.util.Map<String, String> fixedParts);
    List<TranslationResult> translateBatch(TranslateBatchRequest request);
    List<TranslationResult> shortenBatch(List<ShortenRow> rows);
    /** 기존 한국어를 보고 행마다 유지(KEEP)·수정(FIX)을 판정한다. 틀린 곳이 없으면 고치지 않는다. */
    List<ProofreadResult> proofreadBatch(ProofreadBatchRequest request);
    /** 확정 용어가 빠진 기존 한국어에 그 용어를 넣어 최소한으로 고친다. */
    List<TranslationResult> fixTermsBatch(List<FixTermsRow> rows);
    /** 한 행의 한국어를 더 짧게 고친 후보들(조금 줄인 것부터 가장 많이 줄인 것 순). 거르기는 부르는 쪽이 한다. */
    List<String> shortenOptions(ShortenOptionsRequest request);
    boolean ping();
    /** 단어장 추천 창 표기 갈림의 구절 분류. 모르는 분류·빠진 구절은 부르는 쪽이 거른다. */
    default List<PhraseCategory> classifyPhrases(List<PhraseInput> phrases) { return List.of(); }
    /**
     * tool calling 한 번(2026-10-08). messages는 OpenAI chat 형식 그대로(system·user·assistant(tool_calls)·tool).
     * 응답 assistant 메시지의 글과 도구 호출을 돌려준다. 반복은 ToolLoop가 한다.
     */
    default AssistantTurn chatTools(String stage, List<Map<String, Object>> messages, List<ToolSpec> tools) { throw new UnsupportedOperationException(); }
    /** parameters = JSON Schema(object). */
    record ToolSpec(String name, String description, Map<String, Object> parameters) {}
    /** arguments = 모델이 보낸 JSON 문자열 그대로. */
    record ToolCall(String id, String name, String arguments) {}
    /** content는 없을 수 있다(도구만 부를 때). */
    record AssistantTurn(String content, List<ToolCall> calls) {}
    record TermExtraction(String src, String type) {}
    /** ko: 이 게임에서 지금 쓰는 번역, example: 그 구절이 든 원문 한 줄, hint: 옛 용어집의 유형(없으면 null). */
    record PhraseInput(String src, String ko, String example, String hint) {}
    record PhraseCategory(String src, String category) {}
    /** in: "ko"(번역) 또는 "src"(원문). contains가 비면 모든 행. */
    record CommandFilter(String in, String contains) {}
    /** regex=false면 find를 글자 그대로 찾아 replace로 바꾼다. true면 Java 정규식·$1 그룹. */
    record CommandStep(String find, String replace, boolean regex) {}
    record TermInput(String src, String type) {}
    record Candidate(String text, String style) {}
    record CandidateSuggestion(String src, List<Candidate> candidates) {}
    record TranslateRow(String index, String cn, String en) {}
    /** 번역 메모리에서 찾은 비슷한 문장의 기존 번역. 프롬프트에 참고용으로 들어간다. */
    record Reference(String cn, String ko) {}
    record TranslateBatchRequest(String basis, String instructions, Map<String, String> glossary, List<TranslateRow> rows, List<Reference> references) {}
    record TranslationResult(String index, String ko) {}
    record ProofreadRow(String index, String cn, String en, String ko) {}
    record ProofreadBatchRequest(String instructions, Map<String, String> glossary, List<ProofreadRow> rows) {}
    /** verdict: KEEP | FIX. FIX일 때만 ko(고친 문장)와 reason(짧은 사유)이 있다. */
    record ProofreadResult(String index, String verdict, String ko, String reason) {}
    /** missing: 이 행에 빠진 확정 용어(원문 → 확정 번역, 메모가 있으면 메모째). */
    record FixTermsRow(String index, String cn, String en, String ko, Map<String, String> missing) {}
    record ShortenRow(String index, String cn, String ko, int maxChars) {}
    /** glossary: 이 원문에 걸리는 확정 용어(메모째). notes: 단어장 메모 전용 항목의 지시. 없으면 null. */
    record ShortenOptionsRequest(String cn, String en, String ko, int maxChars, Map<String, String> glossary, String notes) {}
}
