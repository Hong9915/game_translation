package com.gametrans.connector;

import com.gametrans.reviewer.ReviewerService.ReviewerRef;

public final class ConnectorDtos {
    private ConnectorDtos() {}

    public record LoginRequest(String loginId, String password) {}

    public record AccountDto(Long accountId, boolean disabled, ReviewerRef reviewer) {}

    public record SheetDto(Long configId, Long projectId, String fileName, String sheet, boolean finalReview) {}

    /** mode: "APPEND"(기본) | "REPLACE". note가 null이면 비고는 건드리지 않고, needsCheck가 null이면 그 체크는 안 바꾼다. */
    public record NoteItem(Long segmentId, String note, Boolean needsCheck, String mode) {}

    public record NotesRequest(java.util.List<NoteItem> items) {}

    public record NoteResult(Long segmentId, String before, String after, boolean needsCheck) {}

    /** autoBase=true면 propose가 base를 그 행 latest_ko로 채우고(저장 때는 null), srcMissing=true는 원문에 용어가 없는 행(체크 꺼 둠). 옛 JSON은 둘 다 null. */
    public record ProposalItem(Long segmentId, String text, String base, String reason, Boolean autoBase, Boolean srcMissing) {}

    public record ProposeRequest(String title, java.util.List<ProposalItem> items) {}

    public record ProposeResult(Long proposalId, boolean presentNow, java.util.List<com.gametrans.translate.TranslateDtos.Skip> lockedRows) {}

    public record ProposalSummary(Long id, String title, int rowCount, java.time.Instant createdAt) {}

    /** 행이 없어졌으면 segmentId·base·text·reason만 차고 나머지는 null, lockedReason="행이 없어짐". */
    public record ProposalRow(Long segmentId, Long projectId, String fileName, String sheet, Integer rowNo, String indexLabel, String src,
                              String fix, String base, String text, String reason, boolean finalReview, String lockedReason, boolean srcMissing) {}

    public record ApplyRequest(java.util.List<Long> segmentIds) {}

    public record ProposalResult(int saved, int unchanged, int propagated, java.util.List<Long> excluded,
                                 java.util.List<com.gametrans.translate.TranslateDtos.Skip> skippedRows, int finalMarked, java.util.List<Long> activityIds) {}

    public record ProposalDetail(Long id, Long reviewerId, Long gameId, String title, String status, java.time.Instant createdAt,
                                 java.time.Instant decidedAt, java.util.List<ProposalRow> rows, ProposalResult result, String termSrc) {}

    public record ReplaceRequest(Long gameId, Long projectId, String sheet, String find, String replace, String src, String reason, String title,
                                 java.util.List<Long> exceptRowIds) {}

    public record ReplaceSample(Long rowId, String before, String after) {}

    public record ReplaceResult(Long proposalId, boolean presentNow, int matched, int proposed, int locked, int srcMissing,
                                java.util.List<ReplaceSample> samples, boolean termCandidate) {}
}
