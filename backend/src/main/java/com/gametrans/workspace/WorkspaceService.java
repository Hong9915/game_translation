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

    public WorkspaceService(SegmentRepository segments, RevisionService revisions, ReviewerService reviewers, NormalizeConfigRepository configs,
                             ProjectRepository projects, jakarta.persistence.EntityManager em, com.gametrans.josa.JosaRequests josaRequests) {
        this.em = em;
        this.josaRequests = josaRequests;
        this.segments = segments;
        this.revisions = revisions;
        this.reviewers = reviewers;
        this.configs = configs;
        this.projects = projects;
    }

    public RowsResponse rows(Long projectId, String sheet, RowFilter filter, String q, Long me, int page, int size) {
        if (sheet == null || sheet.isBlank()) throw new IllegalArgumentException("시트를 지정하세요.");
        Long gameId = projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + projectId))
                .getGame().getId();

        List<Segment> content;
        long total;
        if (filter == RowFilter.DUP_CONFLICT) {
            // DUP_CONFLICT는 행 하나만 봐서는 판정할 수 없어 Specification으로 짜지 않는다 — 불일치 해시를 게임에서 한 번만
            // 구해 시트 행과 해시 조인하는 네이티브 쿼리로 거른다(느린 계획을 피하는 이유는 SegmentRepository.dupConflictPage 참고).
            // 시트·제외 조건은 inSheet와, 검색은 search와 같은 뜻이다.
            String pattern = SegmentSpecs.likePattern(q);
            content = segments.dupConflictPage(gameId, projectId, sheet, pattern, (long) page * size, size);
            total = segments.dupConflictCount(gameId, projectId, sheet, pattern);
        } else {
            var spec = SegmentSpecs.inSheet(projectId, sheet).and(SegmentSpecs.search(q)).and(SegmentSpecs.filter(filter, me));
            Page<Segment> found = segments.findAll(spec, PageRequest.of(page, size, Sort.by("rowNo", "pairIndex")));
            content = found.getContent();
            total = found.getTotalElements();
        }
        List<Long> ids = content.stream().map(Segment::getId).toList();
        Map<Long, List<TranslationRevision>> revs = revisions.bySegmentIds(ids);
        Map<Long, Reviewer> who = reviewers.byId();

        // dupCount는 페이지에 실제로 나온 행의 해시로만 좁혀서 묻는다 — 게임 전체의 중복 소스를 매 페이지 통째로
        // 불러오면(과거 620MiB 사고와 같은 모양) 빈 페이지에서도 게임 전체를 읽게 된다.
        Map<String, Long> dupCounts = new HashMap<>();
        if (!content.isEmpty()) {
            List<String> pageHashes = content.stream().map(s -> md5(s.getSrcText())).distinct().toList();
            for (SegmentRepository.SrcCount c : segments.countBySourceInGame(gameId, pageHashes)) dupCounts.put(c.getHash(), c.getRows());
        }

        List<RowDto> out = new ArrayList<>();
        for (Segment s : content) {
            int dupCount = dupCounts.getOrDefault(md5(s.getSrcText()), 0L).intValue();
            out.add(row(s, revs.getOrDefault(s.getId(), List.of()), who, dupCount));
        }
        return new RowsResponse(out, page, size, total);
    }

    /**
     * 목록(rows)과 같은 조건으로 고른 행의 id만. page·size가 둘 다 있으면 그 쪽만, 없으면 조건에 걸린 전부.
     * 머리글 일괄 체크의 대상이다 — 목록과 대상이 어긋나지 않게 같은 Specification·같은 DUP_CONFLICT 쿼리를 쓴다.
     */
    public List<Long> ids(Long projectId, String sheet, RowFilter filter, String q, Long me, Integer page, Integer size) {
        if (sheet == null || sheet.isBlank()) throw new IllegalArgumentException("시트를 지정하세요.");
        boolean paged = page != null && size != null;
        if (filter == RowFilter.DUP_CONFLICT) {
            Long gameId = projects.findById(projectId)
                    .orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + projectId)).getGame().getId();
            long offset = paged ? (long) page * size : 0;
            int limit = paged ? size : Integer.MAX_VALUE;
            return segments.dupConflictPage(gameId, projectId, sheet, SegmentSpecs.likePattern(q), offset, limit).stream().map(Segment::getId).toList();
        }
        var cb = em.getCriteriaBuilder();
        var cq = cb.createQuery(Long.class);
        var root = cq.from(Segment.class);
        var spec = SegmentSpecs.inSheet(projectId, sheet).and(SegmentSpecs.search(q)).and(SegmentSpecs.filter(filter, me));
        cq.select(root.<Long>get("id")).where(spec.toPredicate(root, cq, cb)).orderBy(cb.asc(root.get("rowNo")), cb.asc(root.get("pairIndex")));
        var query = em.createQuery(cq);
        if (paged) query.setFirstResult(page * size).setMaxResults(size);
        return query.getResultList();
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
        for (SegmentRepository.SrcCount c : segments.countBySourceInGame(gameId, List.of(hash)))
            if (hash.equals(c.getHash())) dupCount = (int) c.getRows();
        List<TranslationRevision> history = revisions.bySegmentIds(List.of(s.getId())).getOrDefault(s.getId(), List.of());
        return row(s, history, reviewers.byId(), dupCount);
    }

    /**
     * 시트 탭·상태바용 요약. sheets는 항상 정규화 설정 순(설정만 있고 세그먼트가 없는 시트는 0행).
     * total·changed·review1·review2·needsCheck·confirmed는 sheet 파라미터가 있으면 그 시트, 없으면 전체 합.
     * changed는 SegmentRepository.countBySheet의 JPQL로 다시 표현하지 않는다 — SegmentSpecs.filter(CHANGED)와
     * "N/A 등 표시 문자열은 빈 값" 기준을 하나로 유지하려고 시트별로 그 Specification을 그대로 센다(시트 수는 적다).
     */
    public Summary summary(Long projectId, String sheet) {
        josaRequests.open(projectId);
        Map<String, SegmentRepository.SheetCount> bySheet = new LinkedHashMap<>();
        for (SegmentRepository.SheetCount c : segments.countBySheet(projectId)) bySheet.put(c.getSheet(), c);

        List<SheetSummary> sheets = new ArrayList<>();
        for (NormalizeConfig cfg : configs.findByProjectIdOrderByIdAsc(projectId)) {
            SegmentRepository.SheetCount c = bySheet.remove(cfg.getSheetName());
            sheets.add(sheetSummary(projectId, cfg.getSheetName(), c));
        }
        for (SegmentRepository.SheetCount c : bySheet.values()) sheets.add(sheetSummary(projectId, c.getSheet(), c));

        String scope = sheet == null || sheet.isBlank() ? null : sheet;
        long total = 0, changed = 0, review1 = 0, review2 = 0, needsCheck = 0, confirmed = 0, josa = 0, josaPending = 0;
        for (SheetSummary s : sheets) {
            if (scope != null && !scope.equals(s.sheet())) continue;
            total += s.total(); changed += s.changed(); review1 += s.review1(); review2 += s.review2(); needsCheck += s.needsCheck(); confirmed += s.confirmed(); josa += s.josa(); josaPending += s.josaPending();
        }

        Map<Long, Reviewer> who = reviewers.byId();
        List<ReviewerSummary> people = new ArrayList<>();
        long unassigned = 0;
        for (SegmentRepository.AssigneeCount c : segments.countByAssignee(projectId, scope)) {
            Reviewer r = c.getAssigneeId() == null ? null : who.get(c.getAssigneeId());
            if (r == null) unassigned += c.getAssigned();
            else people.add(new ReviewerSummary(ReviewerService.ref(r), c.getAssigned(), c.getReviewed()));
        }
        return new Summary(sheets, total, changed, review1, review2, needsCheck, confirmed, unassigned, people, josa, josaPending);
    }

    private SheetSummary sheetSummary(Long projectId, String sheetName, SegmentRepository.SheetCount c) {
        if (c == null) return new SheetSummary(sheetName, 0, 0, 0, 0, 0, 0, 0, 0);
        long changed = segments.count(SegmentSpecs.inSheet(projectId, sheetName).and(SegmentSpecs.filter(RowFilter.CHANGED, null)));
        return new SheetSummary(c.getSheet(), c.getTotal(), changed, c.getReview1(), c.getReview2(), c.getNeedsCheck(), c.getConfirmed(), c.getJosa(), c.getJosaPending());
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
                s.isReview1(), s.isReview2(), s.isNeedsCheck(), s.isConfirmed(), s.getNote(),
                ReviewerService.ref(editor), editor == null ? null : last.getCreatedAt(), dupCount,
                history.stream().map(r -> RevisionDto.of(r, who)).toList());
    }
}
