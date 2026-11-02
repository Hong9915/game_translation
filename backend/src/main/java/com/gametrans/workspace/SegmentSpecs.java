package com.gametrans.workspace;

import com.gametrans.normalize.Segment;
import com.gametrans.text.EmptyMarkers;
import com.gametrans.workspace.WorkspaceDtos.RowFilter;
import com.gametrans.workspace.WorkspaceDtos.SearchIn;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** 검수 그리드 목록의 WHERE 조각들. 전 행을 메모리에 올리지 않고 DB에서 거른다. */
public final class SegmentSpecs {
    private SegmentSpecs() {}

    public static Specification<Segment> inSheet(Long projectId, String sheet) {
        return (root, q, cb) -> cb.and(cb.equal(root.get("projectId"), projectId), cb.equal(root.get("sheetName"), sheet), cb.isFalse(root.get("skipped")));
    }

    /** 범위의 (프로젝트, 시트) 가운데 하나에 든 건너뛰지 않은 행. 시트 하나 범위면 inSheet와 같다. */
    public static Specification<Segment> inScope(SheetScope scope) {
        return (root, q, cb) -> cb.and(cb.or(scope.sheets().stream()
                .map(r -> cb.and(cb.equal(root.get("projectId"), r.projectId()), cb.equal(root.get("sheetName"), r.sheet())))
                .toArray(jakarta.persistence.criteria.Predicate[]::new)), cb.isFalse(root.get("skipped")));
    }

    /**
     * 범위 정렬: 범위 안 순번(게임 순서) → 행 → 열 쌍. 시트 하나 범위면 순번을 빼서 기존 (행, 열 쌍) 정렬과 똑같다
     * (상수 식은 Postgres가 ORDER BY 위치 번호로 읽어 오류를 낸다).
     */
    static List<jakarta.persistence.criteria.Order> scopeOrder(CriteriaBuilder cb, Root<Segment> root, SheetScope scope) {
        List<jakarta.persistence.criteria.Order> out = new java.util.ArrayList<>();
        if (scope.sheets().size() > 1) {
            CriteriaBuilder.Case<Integer> c = cb.selectCase();
            for (int i = 0; i < scope.sheets().size(); i++) {
                SheetScope.SheetRef r = scope.sheets().get(i);
                c = c.when(cb.and(cb.equal(root.get("projectId"), r.projectId()), cb.equal(root.get("sheetName"), r.sheet())), i);
            }
            out.add(cb.asc(c.otherwise(scope.sheets().size())));
        }
        out.add(cb.asc(root.get("rowNo")));
        out.add(cb.asc(root.get("pairIndex")));
        return out;
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
            case FINAL_MARK -> cb.isTrue(root.get("finalMark"));
            case NOT_FINAL_MARK -> cb.isFalse(root.get("finalMark"));
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

    /**
     * 대소문자 무시 부분 일치. LIKE 특수문자는 글자 그대로 찾는다. 기본은 원문·Fix·비고에서(ID는 안 찾는다),
     * EXISTING_KO면 기존 한국어에서만, SRC_CONTAINS면 원문에서만 찾고, SRC_EXACT면 원문이 검색어와 글자 그대로 같은 행만 고른다.
     */
    static Specification<Segment> search(String q, SearchIn in) {
        if (q == null || q.isBlank()) return (root, cq, cb) -> cb.conjunction();
        if (in == SearchIn.SRC_EXACT) return (root, cq, cb) -> cb.equal(root.<String>get("srcText"), q);
        String pattern = likePattern(q);
        if (in == SearchIn.EXISTING_KO)
            return (root, cq, cb) -> cb.like(cb.lower(cb.coalesce(root.<String>get("existingKoText"), "")), pattern, '\\');
        if (in == SearchIn.SRC_CONTAINS)
            return (root, cq, cb) -> cb.like(cb.lower(cb.coalesce(root.<String>get("srcText"), "")), pattern, '\\');
        return (root, cq, cb) -> cb.or(
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
