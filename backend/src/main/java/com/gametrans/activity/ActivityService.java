package com.gametrans.activity;

import com.gametrans.activity.Activity.Kind;
import com.gametrans.activity.ActivityDtos.FeedItem;
import com.gametrans.activity.ActivityDtos.FeedResponse;
import com.gametrans.activity.ActivityDtos.RowItem;
import com.gametrans.activity.ActivityDtos.RevertResult;
import com.gametrans.activity.ActivityDtos.RowsResponse;
import com.gametrans.activity.ActivityRepository.RevisionRowView;
import com.gametrans.common.NotFoundException;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.Project;
import com.gametrans.project.ProjectRepository;
import com.gametrans.review.BulkFixWriter;
import com.gametrans.reviewer.Reviewer;
import com.gametrans.reviewer.ReviewerService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 변경 내역을 만들고(설계 §2) 읽는다(§3). */
@Service
public class ActivityService {
    static final int FEED_MAX = 200;

    private final ActivityRepository repo;
    private final ProjectRepository projects;
    private final ReviewerService reviewers;
    private final SegmentRepository segments;
    private final BulkFixWriter writer;

    public ActivityService(ActivityRepository repo, ProjectRepository projects, ReviewerService reviewers, SegmentRepository segments, BulkFixWriter writer) {
        this.repo = repo;
        this.projects = projects;
        this.reviewers = reviewers;
        this.segments = segments;
        this.writer = writer;
    }

    /** 프로젝트 단위 행동 하나. 부르는 쪽 트랜잭션에 합류한다 — 저장이 롤백되면 이것도 같이 사라진다. */
    @Transactional
    public Long start(Long projectId, Kind kind, Long reviewerId, Map<String, Object> detail) {
        Project p = projects.findById(projectId).orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + projectId));
        return save(p.getGame().getId(), projectId, kind, reviewerId, detail);
    }

    /** 게임 단위 행동(단어장 편집). 그 게임의 모든 프로젝트 피드에 나온다. */
    @Transactional
    public Long startForGame(Long gameId, Kind kind, Long reviewerId, Map<String, Object> detail) {
        return save(gameId, null, kind, reviewerId, detail);
    }

    /**
     * 프로젝트마다 하나씩 필요할 때만 만든다 — 단어장 적용처럼 한 행동이 게임의 여러 파일을 건드릴 때 쓴다.
     * 실제로 리비전을 쓰는 행이 있는 프로젝트에만 activity가 생긴다(0행 항목 없음).
     * 안의 start는 같은 빈 안 호출이라 @Transactional 프록시를 안 타지만, 부르는 쪽(BulkFixWriter.write·saveHuman·applyAll)이
     * 늘 트랜잭션 안이고 repo.save도 자체 트랜잭션이 있어 저장은 된다.
     */
    public Function<Long, Long> lazyPerProject(Kind kind, Long reviewerId, Map<String, Object> detail) {
        Map<Long, Long> made = new HashMap<>();
        return pid -> made.computeIfAbsent(pid, p -> start(p, kind, reviewerId, detail));
    }

    @Transactional(readOnly = true)
    public FeedResponse feed(Long projectId, Long before, int size) {
        Project p = projects.findById(projectId).orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + projectId));
        int n = Math.max(1, Math.min(FEED_MAX, size));
        return feed(repo.feed(projectId, p.getGame().getId(), before == null ? Long.MAX_VALUE : before, PageRequest.of(0, n)), n);
    }

    /** 게임 전체 리뷰 화면: 범위에 든 파일들 + 게임 단위 항목을 시간순(최신부터)으로 합친다. */
    @Transactional(readOnly = true)
    public FeedResponse feedForGame(Long gameId, List<Long> projectIds, Long before, int size) {
        int n = Math.max(1, Math.min(FEED_MAX, size));
        return feed(repo.feedOfProjects(projectIds, gameId, before == null ? Long.MAX_VALUE : before, PageRequest.of(0, n)), n);
    }

    private FeedResponse feed(List<Activity> items, int n) {
        List<Long> ids = items.stream().map(Activity::getId).toList();
        Map<Long, Long> counts = new HashMap<>();
        Map<Long, RevisionRowView> primaries = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : repo.rowCounts(ids)) counts.put((Long) row[0], ((Number) row[1]).longValue());
            for (RevisionRowView v : repo.primaries(ids)) primaries.put(v.getActivityId(), v);
        }
        Map<Long, Reviewer> byId = reviewers.byId();
        List<FeedItem> out = items.stream().map(a -> {
            RowItem primary = hasPrimary(a.getKind()) ? toRow(primaries.get(a.getId())) : null;
            String sheet = primary != null ? primary.sheet()
                    : (a.getKind() == Kind.FIX_TERMS || a.getKind() == Kind.SHORTEN) ? (String) a.getDetail().get("sheet") : null;
            return new FeedItem(a.getId(), a.getKind().name(), ReviewerService.ref(a.getReviewerId() == null ? null : byId.get(a.getReviewerId())),
                    a.getCreatedAt(), counts.getOrDefault(a.getId(), 0L), sheet, primary, a.getDetail(), a.getProjectId());
        }).toList();
        // 꽉 찬 쪽이면 더 있을 수 있다 — 마지막 id를 커서로 준다. 딱 맞아떨어지면 다음 쪽이 비어 오고 그때 끝난다.
        Long next = items.size() == n ? items.get(items.size() - 1).getId() : null;
        return new FeedResponse(out, next);
    }

    /** 펼친 행 목록. EDIT·RETRANSLATE는 대표 행을 뺀다(피드 항목에 이미 보인다). */
    @Transactional(readOnly = true)
    public RowsResponse rows(Long activityId, int page, int size) {
        Activity a = repo.findById(activityId).orElseThrow(() -> new NotFoundException("변경 내역을 찾을 수 없습니다: " + activityId));
        long exclude = hasPrimary(a.getKind()) && a.getDetail().get("segmentId") != null ? ((Number) a.getDetail().get("segmentId")).longValue() : -1L;
        List<RowItem> rows = repo.rowsOf(activityId, exclude, size, page * size).stream().map(ActivityService::toRow).toList();
        return new RowsResponse(rows, page, size, repo.countRowsOf(activityId, exclude));
    }

    /**
     * 되돌리기(2026-09-30 사용자 결정): 그 항목이 건드린 행만, 그 항목 이전 본문으로 새 HUMAN 판을 쓴다. 전파는 타지 않는다.
     * 그 사이 바뀐 행(지금 본문 ≠ 그 항목의 마지막 본문)·확정 행·첫 판 행(이전 본문 없음)은 건너뛴다 — 덮어쓰지 않는다.
     * 조사 고침(JOSA_FIX)만은 확정 행도 되돌린다 — 확정 행까지 고친 항목이라, 건너뛰면 그 행은 되돌릴 길이 없다.
     * 되돌리기 자체가 REVERT 항목으로 남아(파일마다 하나) 다시 되돌릴 수 있다. 단어장 항목은 그대로라 단어장 적용은 다음 일괄 적용 때 다시 바뀐다.
     */
    @Transactional
    public RevertResult revert(Long activityId, Long reviewerId) {
        Activity a = repo.findById(activityId).orElseThrow(() -> new NotFoundException("변경 내역을 찾을 수 없습니다: " + activityId));
        if (a.getKind() == Kind.GLOSSARY_EDIT) throw new IllegalArgumentException("단어장 편집은 되돌릴 수 없습니다. 단어장에서 고치세요.");
        // 행별로 이 항목의 첫 판 이전 본문(before)과 마지막 판 본문(after). rowsOf는 파일·시트·행·판 id 순이라 같은 행의 판이 붙어 온다.
        Map<Long, String> before = new LinkedHashMap<>();
        Map<Long, String> after = new HashMap<>();
        for (RevisionRowView v : repo.rowsOf(activityId, -1L, Integer.MAX_VALUE, 0)) {
            before.putIfAbsent(v.getSegmentId(), v.getPrevText());
            after.put(v.getSegmentId(), v.getNextText());
        }
        boolean skipConfirmed = a.getKind() != Kind.JOSA_FIX;
        List<Segment> candidates = new ArrayList<>();
        for (Segment s : segments.findAllById(before.keySet())) {
            String prev = before.get(s.getId());
            if (prev == null || (skipConfirmed && s.isConfirmed()) ||!after.get(s.getId()).equals(s.getLatestKo())) continue;
            candidates.add(s);
        }
        int reverted = writer.write(candidates, s -> before.get(s.getId()), reviewers.exists(reviewerId) ? reviewerId : null,
                lazyPerProject(Kind.REVERT, reviewerId, Map.of("revertOf", activityId)));
        return new RevertResult(reverted, before.size() - reverted);
    }

    private static boolean hasPrimary(Kind k) { return k == Kind.EDIT || k == Kind.RETRANSLATE; }

    private static RowItem toRow(RevisionRowView v) {
        return v == null ? null : new RowItem(v.getSegmentId(), v.getProjectId(), v.getSheet(), v.getRowNo(), v.getOrdinal(), v.getPrevText(), v.getNextText());
    }

    private Long save(Long gameId, Long projectId, Kind kind, Long reviewerId, Map<String, Object> detail) {
        Activity a = new Activity();
        // 브라우저에 남은 지워진 검토자 id는 FK 위반(500)이 아니라 익명으로 남긴다 — 저장 자체를 막을 이유는 없다.
        a.setGameId(gameId); a.setProjectId(projectId); a.setReviewerId(reviewers.exists(reviewerId) ? reviewerId : null); a.setKind(kind);
        a.setDetail(new HashMap<>(detail));
        return repo.save(a).getId();
    }
}
