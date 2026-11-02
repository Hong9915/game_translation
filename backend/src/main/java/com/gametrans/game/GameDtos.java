package com.gametrans.game;

import java.time.Instant;

public final class GameDtos {
    private GameDtos() {}

    /** 게임 하나와 그 공유 자산 요약: 파일 수, 메모리 문장 수, 마지막 업로드 시각, DeepL 지시문. */
    public record GameDto(Long id, String name, long projects, long memory, Instant lastUploadedAt, Instant createdAt, String deeplInstructions) {}

    public record GameRequest(String name) {}

    public record DeepLInstructionsRequest(String text) {}
}
