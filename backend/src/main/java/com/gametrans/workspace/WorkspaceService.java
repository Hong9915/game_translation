package com.gametrans.workspace;

import com.gametrans.common.NotFoundException;
import com.gametrans.normalize.NormalizeConfig;
import com.gametrans.normalize.NormalizeConfigRepository;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.ProjectRepository;
import com.gametrans.review.ReviewDtos.RevisionDto;
import com.gametrans.reviewer.Reviewer;
import com.gametrans.reviewer.ReviewerService;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import com.gametrans.workspace.WorkspaceDtos.ReviewerSummary;
import com.gametrans.workspace.WorkspaceDtos.RowDto;
import com.gametrans.workspace.WorkspaceDtos.RowFilter;
import com.gametrans.workspace.WorkspaceDtos.RowsResponse;
import com.gametrans.workspace.WorkspaceDtos.SearchIn;
import com.gametrans.workspace.WorkspaceDtos.SheetSummary;
import com.gametrans.workspace.WorkspaceDtos.Summary;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** 검수 워크스페이스 목록·요약. 옛 ReviewService.rows(프로젝트 전 행 로드)와 달리 시트 한 페이지만 DB에서 읽는다. */
@Service
public class WorkspaceService {
    private final SegmentRepository segments;
    private final RevisionService revisions;
    private final ReviewerService reviewers;
    private final NormalizeConfigRepository configs;
    private final ProjectRepository projects;
    private final jakarta.persistence.EntityManager em;
    private final com.gametrans.josa.JosaRequests josaRequests;
    private final SheetScopes scopes;

    public WorkspaceService(SegmentRepository segments, RevisionService revisions, ReviewerService reviewers, NormalizeConfigRepository configs,
                             ProjectRepository projects, jakarta.persistence.EntityManager em, com.gametrans.josa.JosaRequests josaRequests,
                             SheetScopes scopes) {
        this.scopes = scopes;
        this.em = em;
        this.josaRequests = josaRequests;
        this.segments = segments;
        this.revisions = revisions;
        this.reviewers = reviewers;
        this.configs = configs;
        this.projects = projects;
    }

    public RowsResponse rows(SheetScope scope, RowFilter filter, String q, SearchIn qIn, Long me, int page, int size) {
        Long gameId = scope.gameId();
        List<Segment> content;
        long total;
        if (filter == RowFilter.DUP_CONFLICT) {
            // DUP_CONFLICT는 행 하나만 봐서는 판정할 수 없어 Specification으로 짜지 않는다 — 불일치 해시를 게임에서 한 번만
            // 구해 범위 행과 해시 조인하는 네이티브 쿼리로 거른다(느린 계획을 피하는 이유는 SegmentRepository.dupConflictPage 참고).
            // 범위·제외 조건은 inScope와, 검색은 search와 같은 뜻이다.
            String pattern = SegmentSpecs.likePattern(q);
            String in = searchIn(q, qIn);
            String exact = q == null ? "" : q;
            content = segments.dupConflictPage(gameId, scope.finalReview(), scope.projectIds(), scope.joinedKeys(), pattern, in, exact, (long) page * size, size);
            total = segments.dupConflictCount(gameId, scope.finalReview(), scope.projectIds(), scope.joinedKeys(), pattern, in, exact);
        } else {
            var spec = SegmentSpecs.inScope(scope).and(SegmentSpecs.search(q, qIn)).and(SegmentSpecs.filter(filter, me));
            content = page(spec, scope, page * size, size);
            total = segments.count(spec);
        }
        List<Long> ids = content.stream().map(Segment::getId).toList();
        Map<Long, List<TranslationRevision>> revs = revisions.bySegmentIds(ids);
        Map<Long, Reviewer> who = reviewers.byId();

        // dupCount는 페이지에 실제로 나온 행의 해시로만 좁혀서 묻는다 — 게임 전체의 중복 소스를 매 페이지 통째로
        // 불러오면(과거 620MiB 사고와 같은 모양) 빈 페이지에서도 게임 전체를 읽게 된다.
        Map<String, Long> dupCounts = new HashMap<>();
        if (!content.isEmpty()) {
            List<String> pageHashes = content.stream().map(s -> md5(s.getSrcText())).distinct().toList();
            for (SegmentRepository.SrcCount c : segments.countBySourceInGame(gameId, scope.finalReview(), pageHashes)) dupCounts.put(c.getHash(), c.getRows());
        }

        List<RowDto> out = new ArrayList<>();
        for (Segment s : content) {
            int dupCount = dupCounts.getOrDefault(md5(s.getSrcText()), 0L).intValue();
            out.add(row(s, revs.getOrDefault(s.getId(), List.of()), who, dupCount));
        }
        return new RowsResponse(out, page, size, total);
    }

    /** 네이티브 DUP_CONFLICT 쿼리에 넘길 찾을 칸 이름. 검색어가 없으면 SegmentSpecs.search처럼 칸을 가리지 않는다(DEFAULT + '%'). */
    private static String searchIn(String q, SearchIn qIn) {
        return q == null || q.isBlank() || qIn == null ? SearchIn.DEFAULT.name() : qIn.name();
    }

    /** 범위 순번(게임 순서) → 행 → 열 쌍 순으로 offset부터 limit개. Sort로는 범위 순번 같은 식을 줄 수 없어 Criteria로 직접 짠다. */
    private List<Segment> page(org.springframework.data.jpa.domain.Specification<Segment> spec, SheetScope scope, int offset, int limit) {
        var cb = em.getCriteriaBuilder();
        var cq = cb.createQuery(Segment.class);
        var root = cq.from(Segment.class);
        cq.select(root).where(spec.toPredicate(root, cq, cb))
                .orderBy(SegmentSpecs.scopeOrder(cb, root, scope));
        return em.createQuery(cq).setFirstResult(offset).setMaxResults(limit).getResultList();
    }

    /**
     * 목록(rows)과 같은 조건으로 고른 행의 id만. page·size가 둘 다 있으면 그 쪽만, 없으면 조건에 걸린 전부.
     * 머리글 일괄 체크의 대상이다 — 목록과 대상이 어긋나지 않게 같은 Specification·같은 DUP_CONFLICT 쿼리를 쓴다.
     */
    public List<Long> ids(SheetScope scope, RowFilter filter, String q, SearchIn qIn, Long me, Integer page, Integer size) {
        boolean paged = page != null && size != null;
        if (filter == RowFilter.DUP_CONFLICT) {
            long offset = paged ? (long) page * size : 0;
            int limit = paged ? size : Integer.MAX_VALUE;
            return segments.dupConflictPage(scope.gameId(), scope.finalReview(), scope.projectIds(), scope.joinedKeys(), SegmentSpecs.likePattern(q), searchIn(q, qIn), q == null ? "" : q, offset, limit)
                    .stream().map(Segment::getId).toList();
        }
        var cb = em.getCriteriaBuilder();
        var cq = cb.createQuery(Long.class);
        var root = cq.from(Segment.class);
        var spec = SegmentSpecs.inScope(scope).and(SegmentSpecs.search(q, qIn)).and(SegmentSpecs.filter(filter, me));
        cq.select(root.<Long>get("id")).where(spec.toPredicate(root, cq, cb))
                .orderBy(SegmentSpecs.scopeOrder(cb, root, scope));
        var query = em.createQuery(cq);
        if (paged) query.setFirstResult(page * size).setMaxResults(size);
        return query.getResultList();
    }

    /** 주어진 id 가운데 범위 안의 건너뛰지 않은 행만(범위 순서). 일괄 체크·도구가 범위 밖 행을 건드리지 않게 한다. */
    public List<Long> idsWithin(SheetScope scope, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        var cb = em.getCriteriaBuilder();
        var cq = cb.createQuery(Long.class);
        var root = cq.from(Segment.class);
        var spec = SegmentSpecs.inScope(scope);
        cq.select(root.<Long>get("id")).where(cb.and(spec.toPredicate(root, cq, cb), root.get("id").in(ids)))
                .orderBy(SegmentSpecs.scopeOrder(cb, root, scope));
        return em.createQuery(cq).getResultList();
    }

    /**
     * 행 하나를 목록과 같은 모양으로. 행 단위 쓰기(행 번역 등)의 응답용 — dupCount도 목록처럼 그 게임 안의 같은 원문 행 수로 센다.
     * s는 호출한 쪽이 방금 바꾼 칸(term_miss 등)을 반영해 둔 엔티티여야 한다.
     */
    public RowDto row(Segment s) {
        Long gameId = projects.findById(s.getProjectId())
                .orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + s.getProjectId()))
                .getGame().getId();
        String hash = md5(s.getSrcText());
        int dupCount = 0;
        for (SegmentRepository.SrcCount c : segments.countBySourceInGame(gameId, s.isFinalReview(), List.of(hash)))
            if (hash.equals(c.getHash())) dupCount = (int) c.getRows();
        List<TranslationRevision> history = revisions.bySegmentIds(List.of(s.getId())).getOrDefault(s.getId(), List.of());
        return row(s, history, reviewers.byId(), dupCount);
    }

    /**
     * 파일 화면의 시트 탭·상태바용 요약. sheets는 설정한 시트를 워크북 순서(sheet_index, 순번 없는 옛 설정은 뒤에서 설정 순)로
     * 먼저 두고(설정만 있고 세그먼트가 없는 시트는 0행), 설정 없이
     * 세그먼트만 남은 시트는 그 뒤. total·changed 등 수치는 sheet 파라미터가 있으면 그 시트, 없으면 전체 합.
     */
    public Summary summary(Long projectId, String sheet) {
        josaRequests.open(projectId);
        List<SheetScope.SheetRef> listed = new ArrayList<>(scopes.projectSheets(projectId));
        java.util.Set<String> named = new java.util.HashSet<>(listed.stream().map(SheetScope.SheetRef::sheet).toList());
        for (SegmentRepository.SheetCount c : segments.countBySheet(projectId))
            if (named.add(c.getSheet())) listed.add(new SheetScope.SheetRef(null, projectId, c.getSheet(),
                    segments.existsByProjectIdAndSheetNameAndFinalReviewTrue(projectId, c.getSheet())));
        String scope = sheet == null || sheet.isBlank() ? null : sheet;
        return summarize(listed, r -> scope == null || scope.equals(r.sheet()));
    }

    /** 게임 화면 요약. sheets는 게임의 설정 시트 전부(게임 순서), 수치는 범위 안 시트의 합. */
    public Summary summary(SheetScope scope) {
        for (Long pid : scope.projectIds()) josaRequests.open(pid);
        java.util.Set<String> keys = new java.util.HashSet<>(scope.sheets().stream().map(SheetScope.SheetRef::key).toList());
        return summarize(scopes.gameSheets(scope.gameId()), r -> keys.contains(r.key()));
    }

    /**
     * changed는 SegmentRepository.countBySheet의 JPQL로 다시 표현하지 않는다 — SegmentSpecs.filter(CHANGED)와
     * "N/A 등 표시 문자열은 빈 값" 기준을 하나로 유지하려고 시트별로 그 Specification을 그대로 센다(시트 수는 적다).
     */
    private Summary summarize(List<SheetScope.SheetRef> listed, java.util.function.Predicate<SheetScope.SheetRef> counted) {
        Map<Long, Map<String, SegmentRepository.SheetCount>> byProject = new HashMap<>();
        Map<Long, String> fileNames = new HashMap<>();
        for (SheetScope.SheetRef r : listed) {
            byProject.computeIfAbsent(r.projectId(), pid -> {
                Map<String, SegmentRepository.SheetCount> m = new HashMap<>();
                for (SegmentRepository.SheetCount c : segments.countBySheet(pid)) m.put(c.getSheet(), c);
                return m;
            });
            fileNames.computeIfAbsent(r.projectId(), pid -> projects.findById(pid).map(com.gametrans.project.Project::getFileName).orElse(""));
        }
        List<SheetSummary> sheets = new ArrayList<>();
        long total = 0, changed = 0, review1 = 0, review2 = 0, review3 = 0, needsCheck = 0, confirmed = 0, josa = 0, josaPending = 0, finalMark = 0;
        Map<Long, long[]> perAssignee = new LinkedHashMap<>();   // assigneeId(null은 -1) → [assigned, reviewed]
        for (SheetScope.SheetRef r : listed) {
            boolean inScope = counted.test(r);
            SheetSummary s = sheetSummary(r, byProject.get(r.projectId()).get(r.sheet()), fileNames.get(r.projectId()), inScope);
            sheets.add(s);
            if (!inScope) continue;
            total += s.total(); changed += s.changed(); review1 += s.review1(); review2 += s.review2(); review3 += s.review3(); needsCheck += s.needsCheck(); confirmed += s.confirmed(); josa += s.josa(); josaPending += s.josaPending(); finalMark += s.finalMark();
            for (SegmentRepository.AssigneeCount c : segments.countByAssignee(r.projectId(), r.sheet())) {
                long[] acc = perAssignee.computeIfAbsent(c.getAssigneeId() == null ? -1L : c.getAssigneeId(), k -> new long[2]);
                acc[0] += c.getAssigned(); acc[1] += c.getReviewed();
            }
        }

        Map<Long, Reviewer> who = reviewers.byId();
        List<ReviewerSummary> people = new ArrayList<>();
        long unassigned = 0;
        for (Map.Entry<Long, long[]> e : new java.util.TreeMap<>(perAssignee).entrySet()) {
            Reviewer r = e.getKey() == -1L ? null : who.get(e.getKey());
            if (r == null) unassigned += e.getValue()[0];
            else people.add(new ReviewerSummary(ReviewerService.ref(r), e.getValue()[0], e.getValue()[1]));
        }
        return new Summary(sheets, total, changed, review1, review2, review3, needsCheck, confirmed, unassigned, people, josa, josaPending, finalMark);
    }

    /**
     * 범위 밖 시트의 changed는 세지 않고 0으로 둔다(최종 리뷰 I1) — 화면은 합계만 보이는데, 게임 화면은 이벤트마다 요약을
     * 다시 읽으므로 시트마다 도는 이 count가 게임 전체를 매번 훑게 된다. 나머지 수치는 countBySheet 한 번에 나온다.
     */
    private SheetSummary sheetSummary(SheetScope.SheetRef r, SegmentRepository.SheetCount c, String fileName, boolean countChanged) {
        if (c == null) return new SheetSummary(r.sheet(), 0, 0, 0, 0, 0, 0, 0, 0, 0, r.configId(), r.projectId(), fileName, r.finalReview(), 0);
        long changed = !countChanged ? 0 : segments.count(SegmentSpecs.inSheet(r.projectId(), r.sheet()).and(SegmentSpecs.filter(RowFilter.CHANGED, null)));
        return new SheetSummary(r.sheet(), c.getTotal(), changed, c.getReview1(), c.getReview2(), c.getReview3(), c.getNeedsCheck(), c.getConfirmed(),
                c.getJosa(), c.getJosaPending(), r.configId(), r.projectId(), fileName, r.finalReview(), c.getFinalMark());
    }

    // SegmentSpecs.norm(SQL)과 같은 기준: 빈칸뿐 아니라 N/A·None 같은 표시 문자열도 "비교 대상 없음"으로 본다.
    static String norm(String s) { return com.gametrans.text.EmptyMarkers.norm(s); }

    /** Postgres md5()와 같은 값을 자바에서 만든다 — UTF-8 바이트의 MD5 소문자 16진수. */
    static String md5(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("MD5").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static RowDto row(Segment s, List<TranslationRevision> history, Map<Long, Reviewer> who, int dupCount) {
        TranslationRevision last = history.isEmpty() ? null : history.get(history.size() - 1);
        // fix는 캐시(latest_ko)가 아니라 방금 읽은 리비전에서 — 같은 요청 안에서 캐시가 뒤처질 일은 없지만 한 곳만 믿는 게 단순하다
        String fix = last == null ? null : last.getText();
        Reviewer editor = last == null || last.getReviewerId() == null ? null : who.get(last.getReviewerId());
        return new RowDto(s.getId(), s.getRowNo(), s.getPairIndex(), s.getIndexLabel(), s.getKeyText(), s.getSrcText(), s.getEnText(),
                s.getExistingKoText(), fix, !Objects.equals(norm(fix), norm(s.getExistingKoText())),
                com.gametrans.text.GlossaryCheck.parse(s.getTermMiss()),
                s.isJosaIgnored() ? List.of() : com.gametrans.josa.JosaHints.parse(s.getJosaHint()),
                ReviewerService.ref(s.getAssigneeId() == null ? null : who.get(s.getAssigneeId())),
                s.isReview1(), s.isReview2(), s.isReview3(), s.isNeedsCheck(), s.isConfirmed(), s.isFinalReview(), s.isFinalMark(), s.getNote(),
                ReviewerService.ref(editor), editor == null ? null : last.getCreatedAt(), dupCount,
                history.stream().map(r -> RevisionDto.of(r, who)).toList(), s.getProjectId(), s.getSheetName());
    }
}
