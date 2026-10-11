package com.gametrans.workspace;

import com.gametrans.review.ReviewDtos.RevisionDto;
import com.gametrans.reviewer.ReviewerService.ReviewerRef;
import java.time.Instant;
import java.util.List;

public final class WorkspaceDtos {
    private WorkspaceDtos() {}

    /** 검수 그리드의 한 행(칸). fix = 최신 리비전(segment.latest_ko), changed = fix가 기존 한국어와 다름(양쪽 trim 비교). */
    public record RowDto(Long id, int rowNo, int pairIndex, String indexLabel, String keyText, String src, String en, String existingKo, String fix,
                         boolean changed, List<String> termMiss, List<com.gametrans.josa.JosaHint> josaHints, ReviewerRef assignee,
                         boolean review1, boolean review2, boolean needsCheck, boolean confirmed, String note, ReviewerRef lastEditor, Instant lastEditedAt,
                         int dupCount, List<RevisionDto> history) {}
    public record RowsResponse(List<RowDto> rows, int page, int size, long total) {}

    /**
     * MINE은 헤더의 검토자 담당 행. 익명이면 빈 목록. DUP_CONFLICT는 같은 게임 안에서 원문이 같은데 최신 한국어가 갈린 행.
     * NOT_REVIEW1·NOT_REVIEW2·NOT_CONFIRMED는 1차·2차·확정 체크가 없는 행, NEEDS_CHECK는 확인 필요가 켜진 행.
     * JOSA는 조사 제안이 있고 무시하지 않은 행(설계 2026-10-07).
     */
    public enum RowFilter { ALL, CHANGED, NOT_REVIEW1, NOT_REVIEW2, NOT_CONFIRMED, NEEDS_CHECK, MINE, UNASSIGNED, TERM_MISS, EMPTY_FIX, JOSA, DUP_CONFLICT }

    public record SheetSummary(String sheet, long total, long changed, long review1, long review2, long needsCheck, long confirmed, long josa, long josaPending) {}
    /** done: 그 검토자 담당 행 가운데 2차 체크된 수. */
    public record ReviewerSummary(ReviewerRef reviewer, long assigned, long done) {}
    /** sheets는 항상 프로젝트 전체(정규화 설정 순). 나머지 수치는 sheet 파라미터가 있으면 그 시트, 없으면 전체. */
    public record Summary(List<SheetSummary> sheets, long total, long changed, long review1, long review2, long needsCheck,
                          long confirmed, long unassigned, List<ReviewerSummary> reviewers, long josa, long josaPending) {}
}
