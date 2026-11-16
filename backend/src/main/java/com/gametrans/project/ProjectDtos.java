package com.gametrans.project;

import java.time.Instant;

public final class ProjectDtos {
    private ProjectDtos() {}

    /** finalReview: 시트 중 하나라도 최종 리뷰 버전(수정 한국어 열)으로 정규화한 파일 — 게임 화면 파일 목록의 "최종 리뷰" 배지.
     *  reviewRows·confirmedRows: 정규화한 검수 행 수(건너뛴 행 빼고)와 그중 확정한 행 수 — 파일 목록의 진행 막대. */
    public record ProjectDto(Long id, String fileName, Long gameId, String gameName, int sheetCount, int rowCount, Instant uploadedAt, int currentStep, boolean reviewOnly,
                             boolean finalReview, long reviewRows, long confirmedRows) {
        public static ProjectDto of(Project p, boolean finalReview, long[] progress) {
            return new ProjectDto(p.getId(), p.getFileName(), p.getGame().getId(), p.getGameName(), p.getSheetCount(), p.getRowCount(), p.getUploadedAt(), p.getCurrentStep(), p.isReviewOnly(),
                    finalReview, progress == null ? 0 : progress[0], progress == null ? 0 : progress[1]);
        }
    }

    /** gameId나 gameName 중 하나로 게임을 옮긴다. gameName은 없으면 새로 만든다. */
    public record ProjectPatch(Long gameId, String gameName, Integer currentStep) {}
}
