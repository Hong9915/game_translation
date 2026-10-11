package com.gametrans.review;

import com.gametrans.common.NotFoundException;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.normalize.SegmentRepository.AssigneeCount;
import com.gametrans.review.ReviewDtos.AssignRequest;
import com.gametrans.review.ReviewDtos.AssignResult;
import com.gametrans.review.ReviewDtos.ReviewProgress;
import com.gametrans.review.ReviewDtos.ReviewerProgress;
import com.gametrans.review.ReviewDtos.SegmentRow;
import com.gametrans.reviewer.Reviewer;
import com.gametrans.reviewer.ReviewerService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공동 검토의 담당 배정과 완료 체크. 담당은 표시·필터용이라 누구나 배정하고, 남의 행도 누구나 고친다. */
@Service
public class AssignmentService {
    private final SegmentRepository segments;
    private final ReviewerService reviewers;
    private final ReviewService review;
    private final ReviewEvents events;

    public AssignmentService(SegmentRepository segments, ReviewerService reviewers, ReviewService review, ReviewEvents events) {
        this.segments = segments;
        this.reviewers = reviewers;
        this.review = review;
        this.events = events;
    }

    @Transactional
    public AssignResult assign(Long projectId, AssignRequest req, Long reviewerId) {
        Reviewer actor = reviewers.resolve(reviewerId);
        Reviewer assignee = reviewers.resolve(req.assigneeId());
        List<Segment> targets = new ArrayList<>();
        if (req.segmentIds() != null && !req.segmentIds().isEmpty()) {
            // 화면의 "행"은 같은 시트·같은 행 번호의 모든 열 쌍이다
            for (Segment s : segments.findByProjectIdAndSkippedFalseAndIdIn(projectId, req.segmentIds()))
                targets.addAll(segments.findByProjectIdAndSkippedFalseAndSheetNameAndRowNoBetween(projectId, s.getSheetName(), s.getRowNo(), s.getRowNo()));
        } else if (req.sheet() != null && req.fromRow() != null && req.toRow() != null) {
            int from = Math.min(req.fromRow(), req.toRow()), to = Math.max(req.fromRow(), req.toRow());
            targets.addAll(segments.findByProjectIdAndSkippedFalseAndSheetNameAndRowNoBetween(projectId, req.sheet(), from, to));
        } else {
            throw new IllegalArgumentException("배정할 행을 고르거나 시트와 행 범위를 주세요.");
        }
        Map<Long, Segment> unique = new LinkedHashMap<>();
        for (Segment s : targets) unique.put(s.getId(), s);
        for (Segment s : unique.values()) s.setAssigneeId(assignee == null ? null : assignee.getId());
        segments.saveAll(unique.values());
        events.assigned(projectId, actor == null ? null : actor.getId(), List.copyOf(unique.keySet()), ReviewerService.ref(assignee));
        return new AssignResult(unique.size());
    }

    @Transactional
    public SegmentRow done(Long segmentId, boolean done, Long reviewerId, Double ratio) {
        Reviewer who = reviewers.resolve(reviewerId);
        Segment s = segments.findById(segmentId).orElseThrow(() -> new NotFoundException("세그먼트를 찾을 수 없습니다: " + segmentId));
        s.setDone(done);
        s.setDoneBy(done && who != null ? who.getId() : null);
        s.setDoneAt(done ? Instant.now() : null);
        segments.save(s);
        SegmentRow row = review.row(segmentId, ratio == null ? review.defaultRatio() : ratio);
        events.segmentUpdated(s.getProjectId(), who == null ? null : who.getId(), row);
        return row;
    }

    /**
     * 검토자별 배정·완료 수와 전체 진행률. sheet가 null이면 프로젝트 전체를 본다.
     * 배정·완료를 누를 때마다 다시 부르는 화면이라 행을 다 읽지 않고 DB에서 집계한다.
     */
    public ReviewProgress progress(Long projectId, String sheet) {
        Map<Long, Reviewer> who = reviewers.byId();
        List<ReviewerProgress> out = new ArrayList<>();
        long unassigned = 0, total = 0, done = 0;
        for (AssigneeCount c : segments.countByAssignee(projectId, sheet)) {   // 담당자 id 순, 미배정 묶음은 맨 뒤
            total += c.getAssigned();
            done += c.getDone();
            // c.getAssigneeId()가 있어도 그 검토자가 지워졌으면(방어적으로) who에 없다 — null을 넘기지 않고 미배정으로 센다.
            Reviewer assignee = c.getAssigneeId() == null ? null : who.get(c.getAssigneeId());
            if (assignee == null) unassigned += c.getAssigned();
            else out.add(new ReviewerProgress(ReviewerService.ref(assignee), c.getAssigned(), c.getDone()));
        }
        return new ReviewProgress(out, unassigned, total, done, segments.sheetNames(projectId));
    }
}
