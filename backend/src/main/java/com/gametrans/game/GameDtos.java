package com.gametrans.game;

import java.time.Instant;

public final class GameDtos {
    private GameDtos() {}

    /** 게임 하나와 그 공유 자산 요약: 파일 수, 공용 용어 수, 메모리 문장 수, 마지막 업로드 시각. */
    public record GameDto(Long id, String name, long projects, long terms, long memory, Instant lastUploadedAt, Instant createdAt) {}

    /**
     * 게임 공용 용어집의 한 줄: 원문, 종류, 확정 번역. conflicts: 이 용어가 품은 더 짧은 용어 중 번역이 반영되지 않은 것("升星→성급 상승").
     * stem: 숫자만 다른 변형(11星英雄)이면 그 기본 단어(星英雄) — 용어집 정리에서 이것으로 접힌다.
     */
    public record GameTermDto(Long id, String srcText, String type, String ko, Instant updatedAt, java.util.List<String> conflicts, String stem) {
        public GameTermDto(Long id, String srcText, String type, String ko, Instant updatedAt) {
            this(id, srcText, type, ko, updatedAt, java.util.List.of(), com.gametrans.text.GlossaryCheck.numericStem(srcText));
        }
    }

    public record GameRequest(String name) {}
}
