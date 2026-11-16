package com.gametrans.llm;

import java.util.List;
import java.util.Map;

public interface LlmClient {
    List<TranslationResult> translateBatch(TranslateBatchRequest request);
    List<TranslationResult> shortenBatch(List<ShortenRow> rows);
    /** 기존 한국어를 보고 행마다 유지(KEEP)·수정(FIX)을 판정한다. 틀린 곳이 없으면 고치지 않는다. */
    List<ProofreadResult> proofreadBatch(ProofreadBatchRequest request);
    /**
     * 검수 그리드의 GPT로 다듬기(2026-11-13). request.instructions는 게임의 다듬기 지시문 전체(기본 + 덧붙임 + 단어장 메모).
     * 응답 형식은 proofreadBatch와 같다(KEEP | FIX + ko + reason). 저장은 부르는 쪽이 사람이 고른 것만 한다.
     */
    List<ProofreadResult> polishBatch(ProofreadBatchRequest request);
    /** 확정 용어가 빠진 기존 한국어에 그 용어를 넣어 최소한으로 고친다. */
    List<TranslationResult> fixTermsBatch(List<FixTermsRow> rows);
    /** 원문 용어를 정한 한국어로 바꿔 쓴다(명령으로 고치기의 용어 바꾸기). 지금 번역이 그 용어를 어떻게 옮겼든(다른 말·영어·빠짐) 찾아 고친다. */
    List<TranslationResult> termSwapBatch(List<FixTermsRow> rows);
    /** 한 행의 한국어를 더 짧게 고친 후보들(조금 줄인 것부터 가장 많이 줄인 것 순). 거르기는 부르는 쪽이 한다. */
    List<String> shortenOptions(ShortenOptionsRequest request);
    boolean ping();
    /** 단어장 추천 창 표기 갈림의 구절 분류. 모르는 분류·빠진 구절은 부르는 쪽이 거른다. */
    default List<PhraseCategory> classifyPhrases(List<PhraseInput> phrases) { return List.of(); }
    /** 교정 규칙 글 종류(설계 2026-11-13 §3): 행마다 코드 하나(E·I·Q·S·H·T·D·N·U). 가벼운 모델. */
    default List<KindResult> classifyTextKinds(List<KindInput> rows) { return List.of(); }
    /** 교정 규칙 문체 GPT 다시 쓰기(설계 2026-11-13 §4): 서술식 어미만 개조식으로. index=행 id, cn=원문, ko=지금 글. */
    default List<TranslationResult> rewriteStyleBatch(List<FixTermsRow> rows) { return List.of(); }
    /** 단어장 요청 용어의 한국어 초안(한 분류씩). 빠진 용어는 부르는 쪽이 실패로 둔다. */
    default List<TermDraft> draftTerms(TermDraftRequest req) { return List.of(); }
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
    /** ko: 이 게임에서 지금 쓰는 번역, example: 그 구절이 든 원문 한 줄, hint: 옛 용어집의 유형(없으면 null). */
    record KindInput(String id, String zh, String ko) {}
    record KindResult(String id, String code) {}
    record PhraseInput(String src, String ko, String example, String hint) {}
    record PhraseCategory(String src, String category) {}
    /** category: 양식 분류(CHARACTER…OTHER). known: 이 게임에서 이미 정한 표기(원문 → 한국어). instructions: 게임 지시문 줄. */
    record TermDraftRequest(String category, List<String> terms, List<Reference> known, List<String> instructions) {}
    /** reason: 한국어 한 문장. */
    record TermDraft(String src, String ko, String reason) {}
    /** in: "ko"(번역) 또는 "src"(원문). contains가 비면 모든 행. */
    record CommandFilter(String in, String contains) {}
    /** regex=false면 find를 글자 그대로 찾아 replace로 바꾼다. true면 Java 정규식·$1 그룹. */
    record CommandStep(String find, String replace, boolean regex) {}
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
