package com.gametrans.project;

import java.time.Instant;

public final class ProjectDtos {
    private ProjectDtos() {}

    public record ProjectDto(Long id, String fileName, Long gameId, String gameName, int sheetCount, int rowCount, Instant uploadedAt, int currentStep, boolean reviewOnly) {
        public static ProjectDto of(Project p) {
            return new ProjectDto(p.getId(), p.getFileName(), p.getGame().getId(), p.getGameName(), p.getSheetCount(), p.getRowCount(), p.getUploadedAt(), p.getCurrentStep(), p.isReviewOnly());
        }
    }

    /** gameId나 gameName 중 하나로 게임을 옮긴다. gameName은 없으면 새로 만든다. */
    public record ProjectPatch(Long gameId, String gameName, Integer currentStep) {}
}
