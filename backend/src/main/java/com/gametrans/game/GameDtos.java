package com.gametrans.game;

import java.time.Instant;

public final class GameDtos {
    private GameDtos() {}

    /** 게임 하나와 그 공유 자산 요약: 파일 수, 메모리 문장 수, 마지막 업로드 시각, DeepL 지시문,
     *  모든 파일의 검수 행 수와 그중 확정 행 수(게임 목록의 진행 막대). */
    public record GameDto(Long id, String name, long projects, long memory, Instant lastUploadedAt, Instant createdAt, String deeplInstructions,
                          long reviewRows, long confirmedRows,
                          String gptTranslateBase, String gptTranslateExtra, String gptPolishBase, String gptPolishExtra) {}

    public record GameRequest(String name) {}

    public record DeepLInstructionsRequest(String text) {}

    /** GPT 덧붙임 지시. null인 필드는 바꾸지 않는다. */
    public record GptInstructionsRequest(String translate, String polish) {}
}
