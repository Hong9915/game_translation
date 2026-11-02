package com.gametrans.project;

import java.time.Instant;

public final class ProjectDtos {
    private ProjectDtos() {}

    /** finalReview: 시트 중 하나라도 최종 리뷰 버전(수정 한국어 열)으로 정규화한 파일 — 게임 화면 파일 목록의 "최종 리뷰" 배지. */
    public record ProjectDto(Long id, String fileName, Long gameId, String gameName, int sheetCount, int rowCount, Instant uploadedAt, int currentStep, boolean reviewOnly,
                             boolean finalReview) {
        public static ProjectDto of(Project p, boolean finalReview) {
            return new ProjectDto(p.getId(), p.getFileName(), p.getGame().getId(), p.getGameName(), p.getSheetCount(), p.getRowCount(), p.getUploadedAt(), p.getCurrentStep(), p.isReviewOnly(),
                    finalReview);
        }
    }

    /** gameId나 gameName 중 하나로 게임을 옮긴다. gameName은 없으면 새로 만든다. */
    public record ProjectPatch(Long gameId, String gameName, Integer currentStep) {}
}
