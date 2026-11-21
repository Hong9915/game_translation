package com.gametrans.connector;

import com.gametrans.reviewer.ReviewerService.ReviewerRef;

public final class ConnectorDtos {
    private ConnectorDtos() {}

    public record LoginRequest(String loginId, String password) {}

    public record AccountDto(Long accountId, boolean disabled, ReviewerRef reviewer) {}

    /** 게임의 파일 한 줄. configuredSheets는 정규화 설정을 한 시트 수. */
    public record FileDto(Long projectId, String fileName, boolean reviewOnly, int configuredSheets) {}

    /** 열 하나의 표본 통계(맨 위 300행). filled는 표본 행 중 글이 있는 칸 %, 나머지는 글이 있는 칸 중 % (정수 0~100). */
    public record ColumnStat(String letter, int filled, int han, int hangul, int latin, int traditional) {}

    /** config·canReplace는 정규화한 시트만(아니면 null). canReplace=false면 이미 작업한 흔적이 있어 다시 만들면 날아간다. */
    public record InspectSheet(String name, int index, int rowCount, java.util.List<java.util.List<String>> top, java.util.List<ColumnStat> columns,
                               com.gametrans.xlsx.ColumnGuesser.Guess guess, com.gametrans.normalize.NormalizeDtos.ConfigDto config, Boolean canReplace) {}

    public record InspectResponse(Long projectId, String fileName, int totalSheets, int offset, java.util.List<InspectSheet> sheets) {}

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

    /** 시트 하나의 정규화 계획. headerRow가 null이면 1, 0이면 헤더 없음. 열 글자는 대소문자 구분 없이 받는다. */
    public record SheetPlan(String sheetName, Integer headerRow, String keyCol, java.util.List<PlanPair> pairs, Boolean srcToSimplified) {}

    /** koCol이 있으면 그 열의 기존 한국어를 읽고(원본 보존), 없으면 새 열에 쓴다. enCol·koCol은 null 가능. */
    public record PlanPair(String srcCol, String enCol, String koCol) {}

    public record NormalizeSheetsRequest(java.util.List<SheetPlan> sheets, Boolean replace) {}

    /** status: OK | REFUSED | FAILED | NOT_RUN. OK가 아니면 stats는 null, warnings는 빈 목록. */
    public record SheetResult(String sheetName, String status, String reason, com.gametrans.normalize.NormalizeDtos.NormalizeStats stats,
                              java.util.List<String> warnings) {}

    public record NormalizeSheetsResponse(java.util.List<SheetResult> results) {}

    public record ReplaceSample(Long rowId, String before, String after) {}

    public record ReplaceResult(Long proposalId, boolean presentNow, int matched, int proposed, int locked, int srcMissing,
                                java.util.List<ReplaceSample> samples, boolean termCandidate) {}
}
