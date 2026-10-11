package com.gametrans.workspace;

import com.gametrans.normalize.Segment;
import com.gametrans.text.EmptyMarkers;
import com.gametrans.workspace.WorkspaceDtos.RowFilter;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

/** 검수 그리드 목록의 WHERE 조각들. 전 행을 메모리에 올리지 않고 DB에서 거른다. */
public final class SegmentSpecs {
    private SegmentSpecs() {}

    public static Specification<Segment> inSheet(Long projectId, String sheet) {
        return (root, q, cb) -> cb.and(cb.equal(root.get("projectId"), projectId), cb.equal(root.get("sheetName"), sheet), cb.isFalse(root.get("skipped")));
    }

    /**
     * trim한 뒤 null과 N/A·None 같은 표시 문자열(EmptyMarkers)은 모두 빈 문자열로 — 정규화가 기존 한국어를
     * trim해서 0차로 넣으므로 공백 차이는 "수정됨"이 아니고, 애초에 기존 번역이 없던 표시 칸(existingKoText="N/A")도
     * "수정됨"의 기준(비교 대상)이 아니다.
     */
    private static Expression<String> norm(CriteriaBuilder cb, Root<Segment> root, String field) {
        Expression<String> trimmed = cb.coalesce(cb.trim(root.<String>get(field)), "");
        CriteriaBuilder.In<String> marker = cb.in(cb.lower(trimmed));
        for (String m : EmptyMarkers.MARKERS) marker.value(m);
        return cb.<String>selectCase().when(marker, "").otherwise(trimmed);
    }

    public static Specification<Segment> filter(RowFilter f, Long me) {
        return (root, q, cb) -> switch (f == null ? RowFilter.ALL : f) {
            case ALL -> cb.conjunction();
            case CHANGED -> cb.notEqual(norm(cb, root, "latestKo"), norm(cb, root, "existingKoText"));
            case NOT_REVIEW1 -> cb.isFalse(root.get("review1"));
            case NOT_REVIEW2 -> cb.isFalse(root.get("review2"));
            case NOT_CONFIRMED -> cb.isFalse(root.get("confirmed"));
            case NEEDS_CHECK -> cb.isTrue(root.get("needsCheck"));
            case MINE -> me == null ? cb.disjunction() : cb.equal(root.get("assigneeId"), me);
            case UNASSIGNED -> cb.isNull(root.get("assigneeId"));
            case TERM_MISS -> cb.isNotNull(root.get("termMiss"));
            case EMPTY_FIX -> cb.equal(norm(cb, root, "latestKo"), "");
            case JOSA -> cb.and(cb.isNotNull(root.get("josaHint")), cb.isFalse(root.get("josaIgnored")));
            // DUP_CONFLICT는 행 하나만 봐서는 판정할 수 없다 — WorkspaceService가 SegmentRepository.dupConflictPage로 따로 거른다.
            // 여기까지 오면 라우팅이 깨진 것이므로 조용히 빈 목록(200 OK)을 돌려주지 않고 크게 실패한다.
            case DUP_CONFLICT -> throw new IllegalStateException("DUP_CONFLICT는 별도 경로로 걸러야 합니다.");
        };
    }

    /** ID·원문·Fix·비고에서 대소문자 무시 부분 일치. LIKE 특수문자는 글자 그대로 찾는다. */
    static Specification<Segment> search(String q) {
        if (q == null || q.isBlank()) return (root, cq, cb) -> cb.conjunction();
        String pattern = likePattern(q);
        return (root, cq, cb) -> cb.or(
                cb.like(cb.lower(cb.coalesce(root.<String>get("keyText"), "")), pattern, '\\'),
                cb.like(cb.lower(cb.coalesce(root.<String>get("srcText"), "")), pattern, '\\'),
                cb.like(cb.lower(cb.coalesce(root.<String>get("latestKo"), "")), pattern, '\\'),
                cb.like(cb.lower(cb.coalesce(root.<String>get("note"), "")), pattern, '\\'));
    }

    /**
     * search가 쓰는 LIKE 패턴(소문자, 역슬래시 이스케이프). 네이티브 쿼리(SegmentRepository.dupConflictPage)도
     * 같은 패턴을 받는다 — 검색어가 없으면 '%'라 coalesce로 null을 빈 문자열로 바꾼 칸에서 모든 행이 걸린다.
     */
    static String likePattern(String q) {
        if (q == null || q.isBlank()) return "%";
        return "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
