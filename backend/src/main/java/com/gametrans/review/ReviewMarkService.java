package com.gametrans.review;

import com.gametrans.common.NotFoundException;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.reviewer.Reviewer;
import com.gametrans.reviewer.ReviewerService;
import com.gametrans.workspace.WorkspaceDtos.RowDto;
import com.gametrans.workspace.WorkspaceDtos.RowFilter;
import com.gametrans.workspace.WorkspaceService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검수 체크(1차·2차·확인 필요·확정)와 비고(설계 2026-09-28). 확정만 자동 변경(같은 원문 전파·단어장 일괄 적용·되돌리기)에서
 * 행을 빼고, 나머지 체크는 표시일 뿐이다. 체크·비고는 서로 독립이다 — 준 칸만 바뀐다.
 * 바뀐 값은 SSE "marks"로 같은 프로젝트를 보는 다른 화면에 알린다.
 */
@Service
public class ReviewMarkService {
    /** null인 칸은 바꾸지 않는다. note는 trim해서 빈 글이면 지운다. */
    public record MarksRequest(Boolean review1, Boolean review2, Boolean needsCheck, Boolean confirmed, String note) {}

    /**
     * 머리글 일괄 체크. ids가 있으면 그 행만(쪽 보기에서 화면에 보이는 행 — 서버가 쪽을 다시 계산하면 그 사이 체크로
     * 필터 결과가 바뀌어 안 보이던 행이 끼어든다). 없으면 목록 API와 같은 조건(sheet 필수, filter·q)에 걸린 전부.
     * ids도 그 프로젝트·시트의 행으로 좁힌다.
     */
    public record BulkMarksRequest(String sheet, RowFilter filter, String q, Integer page, Integer size, List<Long> ids,
                                   Boolean review1, Boolean review2, Boolean needsCheck, Boolean confirmed) {}

    /** 이보다 많이 바꾸면 행 목록 대신 "그 시트를 다시 읽으라"는 신호만 보낸다. update도 이 크기로 나눠 보낸다. */
    static final int BULK_EVENT_LIMIT = 1000;

    /** 실제로 쓸 체크 값. null은 안 바꿈. */
    public record Applied(Boolean review1, Boolean review2, Boolean needsCheck, Boolean confirmed) {
        boolean empty() { return review1 == null && review2 == null && needsCheck == null && confirmed == null; }
    }

    private final SegmentRepository segments;
    private final WorkspaceService workspace;
    private final ReviewerService reviewers;
    private final ReviewEvents events;

    public ReviewMarkService(SegmentRepository segments, WorkspaceService workspace, ReviewerService reviewers, ReviewEvents events) {
        this.segments = segments;
        this.workspace = workspace;
        this.reviewers = reviewers;
        this.events = events;
    }

    @Transactional
    public int bulk(Long projectId, BulkMarksRequest req, Long reviewerId) {
        if (req.sheet() == null || req.sheet().isBlank()) throw new IllegalArgumentException("시트를 지정하세요.");
        Applied a = new Applied(req.review1(), req.review2(), req.needsCheck(), req.confirmed());
        if (a.empty()) throw new IllegalArgumentException("바꿀 체크를 고르세요.");
        Reviewer who = reviewers.resolve(reviewerId);
        List<Long> ids = req.ids() != null
                ? (req.ids().isEmpty() ? List.of() : segments.idsInSheet(projectId, req.sheet(), req.ids()))
                : workspace.ids(projectId, req.sheet(), req.filter(), req.q(), who == null ? null : who.getId(), req.page(), req.size());
        for (int i = 0; i < ids.size(); i += BULK_EVENT_LIMIT)
            segments.updateMarks(ids.subList(i, Math.min(i + BULK_EVENT_LIMIT, ids.size())), a.review1(), a.review2(), a.needsCheck(), a.confirmed());
        Long by = who == null ? null : who.getId();
        if (ids.size() > BULK_EVENT_LIMIT) events.marksBulk(projectId, by, req.sheet());
        else if (!ids.isEmpty()) events.marks(projectId, by, ids, a, null, false);
        return ids.size();
    }

    @Transactional
    public RowDto update(Long segmentId, MarksRequest req, Long reviewerId) {
        Reviewer who = reviewers.resolve(reviewerId);
        if (!segments.existsById(segmentId)) throw new NotFoundException("세그먼트를 찾을 수 없습니다: " + segmentId);
        Applied a = new Applied(req.review1(), req.review2(), req.needsCheck(), req.confirmed());
        boolean noteChanged = req.note() != null;
        String note = noteChanged && !req.note().isBlank() ? req.note().trim() : null;
        if (!a.empty()) segments.updateMarks(List.of(segmentId), a.review1(), a.review2(), a.needsCheck(), a.confirmed());
        if (noteChanged) segments.updateNote(segmentId, note);
        // 벌크 update가 영속성 컨텍스트를 비웠으므로 새로 읽어 방금 쓴 값을 본다
        Segment fresh = segments.findById(segmentId).orElseThrow();
        events.marks(fresh.getProjectId(), who == null ? null : who.getId(), List.of(segmentId), a, note, noteChanged);
        return workspace.row(fresh);
    }
}
