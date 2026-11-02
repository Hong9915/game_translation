package com.gametrans.glossary;

import java.time.Instant;
import java.util.List;

/** 단어장 요청 API의 응답. */
public final class TermRequestDtos {
    private TermRequestDtos() {}

    /** 지난 요청 목록 한 줄. pending = 아직 GPT 초안을 기다리는 항목 수. */
    public record RequestSummary(long id, String fileName, Instant createdAt, int total, int approved, int pending) {}

    /** 요청 항목. existingKo·existingNote = 지금 게임 단어장에 같은 원문(SRC) 항목이 있으면 그 번역·메모, 없으면 null. */
    public record ItemDto(long id, int seq, String category, String src, List<GlossaryDtos.Rendering> renderings, String source,
                          String gptKo, String gptReason, String status, String finalKo, String existingKo, String existingNote) {}

    public record RequestDto(long id, long gameId, String fileName, Instant createdAt, List<ItemDto> items) {}

    /** 승인할 항목 하나. ko = 최종 표기, replace = ko로 바꿀 다른 표기(나머지 renderings 표기는 남긴다). */
    public record ApproveItem(long itemId, String ko, List<String> replace) {}

    public record ApproveRequest(List<ApproveItem> items) {}

    /** approved = 승인한 항목 수, applied = 다른 표기를 바꾼 행 수의 합. */
    public record ApproveResult(int approved, int applied, RequestDto request) {}
}
