package com.gametrans.activity;

import com.gametrans.activity.Activity.Kind;
import com.gametrans.activity.ActivityDtos.FeedItem;
import com.gametrans.activity.ActivityDtos.FeedResponse;
import com.gametrans.activity.ActivityDtos.RowItem;
import com.gametrans.activity.ActivityDtos.RevertResult;
import com.gametrans.activity.ActivityDtos.RowsResponse;
import com.gametrans.activity.ActivityDtos.UndoResult;
import com.gametrans.activity.ActivityRepository.RevisionRowView;
import com.gametrans.common.NotFoundException;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.Project;
import com.gametrans.project.ProjectRepository;
import com.gametrans.review.BulkFixWriter;
import com.gametrans.review.ReviewEvents;
import com.gametrans.review.TermMissService;
import com.gametrans.reviewer.Reviewer;
import com.gametrans.reviewer.ReviewerService;
import com.gametrans.text.GlossaryCheck;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import com.gametrans.translate.TranslationRevisionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final RevisionService revisions;
    private final TranslationRevisionRepository revisionRepo;
    private final TermMissService termMiss;
    private final ReviewEvents events;
    @PersistenceContext private EntityManager em;

    public ActivityService(ActivityRepository repo, ProjectRepository projects, ReviewerService reviewers, SegmentRepository segments, BulkFixWriter writer,
                           RevisionService revisions, TranslationRevisionRepository revisionRepo, TermMissService termMiss, ReviewEvents events) {
        this.repo = repo;
        this.projects = projects;
        this.reviewers = reviewers;
        this.segments = segments;
        this.writer = writer;
        this.revisions = revisions;
        this.revisionRepo = revisionRepo;
        this.termMiss = termMiss;
        this.events = events;
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
     * 커넥터가 켠 최종 수정도 같이 돌린다(flipFinalMarks).
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
        Function<Long, Long> lazy = lazyPerProject(Kind.REVERT, reviewerId, Map.of("revertOf", activityId));
        Map<Long, Long> reverts = new HashMap<>();
        int reverted = writer.write(candidates, s -> before.get(s.getId()), reviewers.exists(reviewerId) ? reviewerId : null,
                pid -> reverts.computeIfAbsent(pid, lazy));
        Map<Long, Map<String, Object>> extra = new HashMap<>();
        flipFinalMarks(a, Map.of(), reverts, extra);
        addDetail(extra);
        return new RevertResult(reverted, before.size() - reverted);
    }

    /**
     * Ctrl+Z/Ctrl+Y 되돌리기(2026-11-13): activityIds를 순서대로 한 트랜잭션에서 되돌린다. revert와 같이 그 사이 바뀐 행·확정 행(JOSA_FIX 빼고)·제외 행은 건너뛴다.
     * revert와 달리 첫 판 행(원래 빈 칸)도 되돌린다 — 이 항목이 쓴 판부터 모두 지워 빈 칸으로 돌린다. 그 뒤 다른 항목(뒤 저장, 앞선 되돌리기의 REVERT)이
     * 쓴 판도 같이 지워지므로, 지운 판 전체(글·작성자·검토자·항목 id)를 순서대로 REVERT 항목의 detail.cleared에 남긴다.
     * detail.cleared가 있는 항목을 되돌리면(= 다시 실행) 지금도 빈 칸인 행에만 그 판들을 순서대로 다시 쓰고, 각 판을 원래 항목에 다시 매단다.
     * 그래서 원래 항목의 피드 행이 돌아오고, 뒤 항목의 되돌리기·다시 실행도 그대로 이어진다 — 되살린 판의 글·순서가 지우기 전과 같으니
     * 뒤 항목들의 "지금 본문 = 그 항목의 마지막 본문" 검사도 지우기 전과 똑같이 맞는다. 다시 실행이 돌려주는 id에는 새 REVERT 대신
     * 빈 칸을 만든 원래 항목(사슬 첫 판의 항목)이 들어간다 — 그 다시 실행을 또 되돌리면 원래 항목을 되돌려 다시 빈 칸이 된다.
     * 원래 항목·검토자가 그 사이 지워졌으면 null로 매단다(첫 판만은 새 REVERT에 매달아 되돌릴 길을 남긴다). 단어장 편집은 판이 없으니 조용히 넘어간다.
     * 넘어온 순서와 상관없이 중복을 걷고 최신 항목(id 큰 것)부터 되돌린다 — 같은 행을 여러 번 고친 걸 한 번에 되돌려도, 돌려준 REVERT id로
     * 다시 실행해도 마지막 판부터 거꾸로 풀려야 "그 사이 바뀐 행"으로 걸리지 않는다.
     * 커넥터가 켠 최종 수정도 같이 돌린다(flipFinalMarks) — 빈 칸으로 돌린 행·되살린 행도 되돌린 행으로 친다.
     */
    @Transactional
    public UndoResult undo(List<Long> activityIds, Long reviewerId) {
        Long rid = reviewers.exists(reviewerId) ? reviewerId : null;
        int reverted = 0, skipped = 0;
        Set<Long> made = new LinkedHashSet<>();
        Set<Long> touched = new LinkedHashSet<>();
        List<Long> ordered = activityIds.stream().filter(Objects::nonNull).distinct().sorted(Comparator.reverseOrder()).toList();
        for (Long id : ordered) {
            // 앞 항목이 쓰고 지운 판이 트리거로 latest_ko에 반영됐으니, 세그먼트를 옛 사본이 아니라 DB에서 다시 읽게 한다.
            em.flush();
            em.clear();
            Activity a = repo.findById(id).orElseThrow(() -> new NotFoundException("변경 내역을 찾을 수 없습니다: " + id));
            if (a.getKind() == Kind.GLOSSARY_EDIT) continue;
            Function<Long, Long> lazy = lazyPerProject(Kind.REVERT, reviewerId, Map.of("revertOf", id, "undo", true));
            Map<Long, Long> reverts = new HashMap<>();   // 프로젝트 → 이 항목을 되돌린 REVERT
            Function<Long, Long> activityFor = pid -> {
                Long aid = reverts.computeIfAbsent(pid, lazy);
                made.add(aid);
                return aid;
            };
            Map<Long, List<Map<String, Object>>> cleared = new LinkedHashMap<>();
            Map<Long, Long> chainRows = new HashMap<>();   // 빈 칸으로 돌리거나 사슬로 되살린 행 → 프로젝트(writer가 쓴 행은 이번 REVERT의 판으로 안다)
            int total = 0, done = 0;

            // 1) 이 항목이 쓴 판: before(첫 판 이전 본문, 없으면 null)와 after(마지막 판 본문). putIfAbsent는 null 값을 없는 것으로 보므로 containsKey로 첫 판만 잡는다.
            Map<Long, String> before = new LinkedHashMap<>();
            Map<Long, String> after = new HashMap<>();
            for (RevisionRowView v : repo.rowsOf(id, -1L, Integer.MAX_VALUE, 0)) {
                if (!before.containsKey(v.getSegmentId())) before.put(v.getSegmentId(), v.getPrevText());
                after.put(v.getSegmentId(), v.getNextText());
            }
            total += before.size();
            if (!before.isEmpty()) {
                Map<Long, Long> firstRev = new HashMap<>();
                for (TranslationRevision r : revisionRepo.findByActivityIdOrderByIdAsc(id)) firstRev.putIfAbsent(r.getSegmentId(), r.getId());
                boolean skipConfirmed = a.getKind() != Kind.JOSA_FIX;
                List<Segment> candidates = new ArrayList<>();
                for (Segment s : segments.findAllById(before.keySet())) {
                    if (s.isSkipped() || (skipConfirmed && s.isConfirmed()) || !after.get(s.getId()).equals(s.getLatestKo())) continue;
                    if (before.get(s.getId()) != null) { candidates.add(s); continue; }
                    // 첫 판 행: 이 항목의 첫 판부터 모두 지운다. 다시 실행 때 그대로 되살리게 지우는 판 전체를 순서대로 남긴다.
                    Long from = firstRev.get(s.getId());
                    List<Map<String, Object>> chain = new ArrayList<>();
                    for (TranslationRevision r : revisionRepo.findBySegmentIdOrderByVersionAsc(s.getId())) {
                        if (r.getId() < from) continue;
                        Map<String, Object> e = new HashMap<>();
                        e.put("text", r.getText());
                        e.put("author", r.getAuthor().name());
                        e.put("reviewerId", r.getReviewerId());
                        e.put("activityId", r.getActivityId());
                        chain.add(e);
                    }
                    Long aid = activityFor.apply(s.getProjectId());
                    revisionRepo.deleteBySegmentIdAndIdGreaterThanEqual(s.getId(), from);
                    segments.updateTermMiss(s.getId(), null);
                    cleared.computeIfAbsent(aid, k -> new ArrayList<>()).add(Map.of("segmentId", s.getId(), "chain", chain));
                    chainRows.put(s.getId(), s.getProjectId());
                    touched.add(s.getProjectId());
                    done++;
                }
                done += writer.write(candidates, s -> before.get(s.getId()), rid, activityFor);
            }

            // 2) 빈 칸으로 돌린 되돌리기를 다시 되돌림(Ctrl+Y): 지금도 빈 칸인 행에만 지운 판들을 순서대로 다시 쓰고 원래 항목에 매단다.
            if (a.getDetail().get("cleared") instanceof List<?> list) {
                Map<Long, Map<?, ?>> bySegment = new LinkedHashMap<>();
                for (Object o : list) if (o instanceof Map<?, ?> m) bySegment.put(((Number) m.get("segmentId")).longValue(), m);
                total += bySegment.size();
                Map<Long, Map<String, String>> glossaries = new HashMap<>();
                Map<Long, Boolean> activityExists = new HashMap<>();
                Map<Long, Boolean> reviewerExists = new HashMap<>();
                for (Segment s : segments.findAllById(bySegment.keySet())) {
                    if (s.isSkipped() || s.isConfirmed() || revisions.current(s.getId()).isPresent()) continue;
                    if (!(bySegment.get(s.getId()).get("chain") instanceof List<?> chain) || chain.isEmpty()) continue;
                    String text = null;
                    boolean first = true;
                    for (Object o : chain) {
                        Map<?, ?> e = (Map<?, ?>) o;
                        text = (String) e.get("text");
                        TranslationRevision.Author author = TranslationRevision.Author.valueOf((String) e.get("author"));
                        Long owner = e.get("activityId") instanceof Number n ? n.longValue() : null;
                        if (owner != null && !activityExists.computeIfAbsent(owner, repo::existsById)) owner = null;
                        Long by = e.get("reviewerId") instanceof Number n ? n.longValue() : null;
                        if (by != null && !reviewerExists.computeIfAbsent(by, reviewers::exists)) by = null;
                        if (first) {
                            if (owner == null) owner = activityFor.apply(s.getProjectId());
                            else made.add(owner);
                            first = false;
                        }
                        revisions.add(s.getId(), text, author, by, owner);
                    }
                    Map<String, String> g = glossaries.computeIfAbsent(s.getProjectId(), termMiss::glossaryFor);
                    segments.updateTermMiss(s.getId(), GlossaryCheck.format(GlossaryCheck.missing(s.getSrcText(), text, g)));
                    chainRows.put(s.getId(), s.getProjectId());
                    touched.add(s.getProjectId());
                    done++;
                }
            }

            Map<Long, Map<String, Object>> extra = new HashMap<>();
            cleared.forEach((aid, list) -> extra.computeIfAbsent(aid, k -> new HashMap<>()).put("cleared", list));
            flipFinalMarks(a, chainRows, reverts, extra);
            addDetail(extra);
            reverted += done;
            skipped += total - done;
        }
        // 판을 지우거나 되살린 프로젝트만 여기서 알린다 — writer.write는 자기가 바꾼 프로젝트를 이미 알린다.
        for (Long pid : touched) events.refresh(pid, reviewerId);
        return new UndoResult(reverted, skipped, new ArrayList<>(made));
    }

    /**
     * 커넥터가 켠 최종 수정을 글과 함께 되돌린다(설계 2026-11-16 undo): a.detail.finalMarked(a가 새로 켠 행)는 끄고, finalUnmarked(a가 끈 행 —
     * 앞선 되돌림의 REVERT)는 다시 켠다. 이번 호출이 실제로 되돌린 행만 바꾼다 — 그 사이 바뀌어 건너뛴 행, 처음부터 켜져 있어 목록에 없던 행은 그대로다.
     * 되돌린 행 = 이번 REVERT(reverts: 프로젝트 → 항목 id)에 매달린 판의 행(BulkFixWriter.write가 건너뛴 후보는 판이 없어 빠진다) + chainRows(빈 칸으로 돌리거나 사슬로 되살린 행).
     * 바꾼 id는 그 파일의 REVERT detail에 반대 키로 남겨(extra), 그 REVERT를 되돌리면(다시 실행) 다시 반대로 바뀐다.
     * 그 파일에 이번 REVERT가 없으면(빈 칸 되살리기가 모든 판을 원래 항목에 다시 매단 경우) 원래 항목의 키가 그대로 그 몫을 한다.
     * 두 키가 없는 항목(POLISH·EDIT 등)은 아무것도 읽지 않고 돌아간다.
     */
    private void flipFinalMarks(Activity a, Map<Long, Long> chainRows, Map<Long, Long> reverts, Map<Long, Map<String, Object>> extra) {
        List<Long> marked = ids(a.getDetail().get("finalMarked"));
        List<Long> unmarked = ids(a.getDetail().get("finalUnmarked"));
        if (marked.isEmpty() && unmarked.isEmpty()) return;
        Map<Long, Long> rows = new HashMap<>(chainRows);   // 되돌린 행 → 프로젝트
        for (Map.Entry<Long, Long> e : reverts.entrySet())
            for (TranslationRevision r : revisionRepo.findByActivityIdOrderByIdAsc(e.getValue())) rows.put(r.getSegmentId(), e.getKey());
        flip(marked, false, "finalUnmarked", rows, reverts, extra);
        flip(unmarked, true, "finalMarked", rows, reverts, extra);
    }

    private void flip(List<Long> ids, boolean on, String key, Map<Long, Long> rows, Map<Long, Long> reverts, Map<Long, Map<String, Object>> extra) {
        List<Long> hit = ids.stream().filter(rows::containsKey).distinct().toList();
        if (hit.isEmpty()) return;
        segments.setFinalMark(hit, on);
        Map<Long, List<Long>> byRevert = new HashMap<>();
        for (Long sid : hit) {
            Long rev = reverts.get(rows.get(sid));
            if (rev != null) byRevert.computeIfAbsent(rev, k -> new ArrayList<>()).add(sid);
        }
        byRevert.forEach((rev, list) -> extra.computeIfAbsent(rev, k -> new HashMap<>()).put(key, list));
    }

    /** REVERT 항목 detail에 키(cleared·finalMarked·finalUnmarked)를 더한다 — 기존 detail(revertOf·undo)을 복사해 합친다. */
    private void addDetail(Map<Long, Map<String, Object>> extra) {
        for (Map.Entry<Long, Map<String, Object>> e : extra.entrySet()) {
            Activity rev = repo.findById(e.getKey()).orElseThrow();
            Map<String, Object> d = new HashMap<>(rev.getDetail());
            d.putAll(e.getValue());
            rev.setDetail(d);
            repo.save(rev);
        }
    }

    /** detail의 id 목록(JSON에서 읽으면 Integer·Long이 섞인다). 없거나 목록이 아니면 빈 목록. */
    private static List<Long> ids(Object o) {
        return o instanceof List<?> list ? list.stream().filter(Number.class::isInstance).map(n -> ((Number) n).longValue()).toList() : List.of();
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
