package com.gametrans.workspace;

import com.gametrans.activity.Activity;
import com.gametrans.activity.ActivityRepository;
import com.gametrans.common.NotFoundException;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.reviewer.Reviewer;
import com.gametrans.reviewer.ReviewerService;
import com.gametrans.reviewer.ReviewerService.ReviewerRef;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 행 하나의 변경 내역(2026-10-08). 리뷰 화면 우클릭 "변경 내역 보기"가 그 행을 열 때 한 번 받는다 — 목록 응답을 무겁게 하지 않으려고 따로 둔다. */
@Service
public class RowHistoryService {
    /** 판 하나. reviewer = 사람이 남긴 판의 검토자(색 포함), activityKind = 이 판을 만든 변경 내역 종류(EDIT·JOSA_FIX 등). 둘 다 없으면 null. */
    public record Entry(int version, String author, String text, Instant createdAt, ReviewerRef reviewer, String activityKind) {}

    private final SegmentRepository segments;
    private final RevisionService revisions;
    private final ReviewerService reviewers;
    private final ActivityRepository activities;

    public RowHistoryService(SegmentRepository segments, RevisionService revisions, ReviewerService reviewers, ActivityRepository activities) {
        this.segments = segments;
        this.revisions = revisions;
        this.reviewers = reviewers;
        this.activities = activities;
    }

    /** 최신 판부터. 다른 프로젝트의 행이면 404. */
    @Transactional(readOnly = true)
    public List<Entry> history(Long projectId, Long segmentId) {
        segments.findById(segmentId).filter(s -> Objects.equals(s.getProjectId(), projectId))
                .orElseThrow(() -> new NotFoundException("행을 찾을 수 없습니다: " + segmentId));
        List<TranslationRevision> list = revisions.bySegmentIds(List.of(segmentId)).getOrDefault(segmentId, List.of());
        Map<Long, Reviewer> who = reviewers.byId();
        List<Long> activityIds = list.stream().map(TranslationRevision::getActivityId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Activity.Kind> kinds = activities.findAllById(activityIds).stream()
                .collect(Collectors.toMap(Activity::getId, Activity::getKind));
        Function<Long, Reviewer> reviewer = id -> id == null ? null : who.get(id);
        return list.stream()
                .sorted(Comparator.comparingInt(TranslationRevision::getVersion).reversed())
                .map(r -> new Entry(r.getVersion(), r.getAuthor().name(), r.getText(), r.getCreatedAt(),
                        ReviewerService.ref(reviewer.apply(r.getReviewerId())),
                        r.getActivityId() == null || !kinds.containsKey(r.getActivityId()) ? null : kinds.get(r.getActivityId()).name()))
                .toList();
    }
}
