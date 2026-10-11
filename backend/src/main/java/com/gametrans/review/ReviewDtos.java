package com.gametrans.review;

import com.gametrans.reviewer.Reviewer;
import com.gametrans.reviewer.ReviewerService.ReviewerRef;
import com.gametrans.translate.TranslationRevision;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ReviewDtos {
    private ReviewDtos() {}

    /** reviewerName: HUMAN 판을 남긴 검토자 이름. 익명이거나 사람이 아니면 null. */
    public record RevisionDto(int version, String author, String text, Instant createdAt, String reviewerName) {
        public static RevisionDto of(TranslationRevision r, Map<Long, Reviewer> reviewers) {
            Reviewer who = r.getReviewerId() == null ? null : reviewers.get(r.getReviewerId());
            return new RevisionDto(r.getVersion(), r.getAuthor().name(), r.getText(), r.getCreatedAt(), who == null ? null : who.getName());
        }
    }
    /** lastEditor: 현재 판을 사람이 남겼을 때 그 검토자. assignee: 담당자(없으면 미배정). */
    /** existingKo: 올린 파일의 기존 한국어 원래 셀 값(없으면 null). 내보내기가 달라진 칸을 고를 때 currentText와 비교한다. */
    public record SegmentRow(Long id, String indexLabel, int pairIndex, String srcText, String enText, boolean isTerm, String currentText,
                             String existingKo, int lenKo, int lenSrc, boolean flagged, List<String> termMiss, String proofNote, List<RevisionDto> history,
                             String sheetName, int rowNo, ReviewerRef assignee, boolean done, ReviewerRef lastEditor, Instant lastEditedAt,
                             boolean proofPending) {}
    /** flagged: 길이 초과. termMiss: 확정 용어 누락. "플래그만 보기"는 둘 다 보여 주고, 짧게 다듬기는 길이 초과만 다룬다. */
    /** proofFixed: 교정 모드에서 GPT가 기존 한국어를 고친 행. proofPending: 기존 한국어가 있는데 아직 유지/수정 판정을 안 받은 행. */
    public record ReviewStats(long filled, long total, long flagged, long termMiss, long revised, long proofFixed, double ratio, long proofPending) {}
    public record SegmentsResponse(ReviewStats stats, List<SegmentRow> rows, int page, int size, long total, List<SheetStat> sheets) {}
    /** 시트 탭용 집계 — 필터와 무관하게 프로젝트 전체 행 기준, 시트 이름순(세그먼트 정렬과 같은 순서). */
    public record SheetStat(String sheet, long total, long flagged, long termMiss, long done) {}
    public record TranslationUpdate(String text) {}
    /** 셀 저장 결과. propagated는 원문이 같아서 같이 바뀐 다른 행 수. */
    public record SaveResult(SegmentRow row, int propagated) {}
    /** sheet가 있으면 그 시트의 플래그 행만 다듬는다(검토 화면의 시트 탭). null이면 프로젝트 전체. */
    public record ShortenRequest(Double ratio, String sheet) {}
    /** segmentIds가 있으면 그 행 가운데, 없으면 sheet(없으면 전체)의 용어 누락 행을 고친다. */
    public record FixTermsRequest(Double ratio, String sheet, List<Long> segmentIds) {}
    /** segmentIds가 있으면 그 칸들이 있는 행 전체(모든 열 쌍)에, 없으면 sheet의 fromRow~toRow에 적용. assigneeId가 null이면 배정 해제. */
    public record AssignRequest(String sheet, List<Long> segmentIds, Integer fromRow, Integer toRow, Long assigneeId) {}
    public record AssignResult(int updated) {}
    public record DoneUpdate(boolean done) {}
    /** assignee: 검토자 id 문자열 또는 "none"(미배정). null이면 거르지 않는다. */
    public record SegmentFilter(Boolean flaggedOnly, boolean proofOnly, String sheet, String assignee, Boolean done, boolean proofPendingOnly) {}
    public record ReviewerProgress(ReviewerRef reviewer, long assigned, long done) {}
    /**
     * reviewers: 이 프로젝트에 담당 행이 있는 검토자만. unassigned·total·done은 건너뛰지 않은 세그먼트 기준.
     * sheets: 프로젝트 전체(건너뛴 세그먼트 제외)의 시트 이름, 이름순 — sheet 파라미터와 무관하게 항상 전체 목록.
     */
    public record ReviewProgress(List<ReviewerProgress> reviewers, long unassigned, long total, long done, List<String> sheets) {}
}
