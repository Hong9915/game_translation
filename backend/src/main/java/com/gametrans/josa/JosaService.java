package com.gametrans.josa;

import com.gametrans.activity.Activity;
import com.gametrans.activity.ActivityService;
import com.gametrans.common.NotFoundException;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.review.BulkFixWriter;
import com.gametrans.workspace.SegmentSpecs;
import com.gametrans.workspace.SheetScope;
import com.gametrans.workspace.WorkspaceDtos.RowFilter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 조사 제안 적용·무시·미리보기(설계 2026-10-07 §5). 확정 행도 고친다(2026-10-07 사용자 결정). */
@Service
public class JosaService {
    static final List<String> KINDS = List.of("HANGUL", "NUMBER", "TAG");
    static final int EXAMPLES = 5;

    public record ApplyRequest(List<Long> segmentIds, String sheet, List<String> kinds) {}
    public record ApplyResult(int changed, int skipped) {}
    public record IgnoreRequest(Long segmentId) {}
    public record Preview(Map<String, Long> counts, Map<String, List<String>> examples) {}

    private final SegmentRepository segments;
    private final BulkFixWriter writer;
    private final ActivityService activities;

    public JosaService(SegmentRepository segments, BulkFixWriter writer, ActivityService activities) {
        this.segments = segments;
        this.writer = writer;
        this.activities = activities;
    }

    @Transactional
    public ApplyResult apply(Long projectId, ApplyRequest req, Long reviewerId) {
        return apply(targets(projectId, req.segmentIds(), req.sheet()), req, reviewerId);
    }

    /** 범위 판(게임 화면). segmentIds가 있으면 그 가운데 범위 안 행만, 없으면 범위의 조사 확인 대상 전부. */
    @Transactional
    public ApplyResult apply(SheetScope scope, ApplyRequest req, Long reviewerId) {
        return apply(targets(scope, req.segmentIds()), req, reviewerId);
    }

    private ApplyResult apply(List<Segment> rows, ApplyRequest req, Long reviewerId) {
        Set<String> kinds = new LinkedHashSet<>(req.kinds() == null ? List.of() : req.kinds());
        kinds.retainAll(KINDS);
        if (kinds.isEmpty()) throw new IllegalArgumentException("고칠 종류를 하나 이상 고르세요.");
        // 확정 행도 고친다(2026-10-07 사용자 결정) — 같은 원문 반영·단어장 일괄 적용과 달리 조사 고침은 받침만 맞추는 일이다.
        int changed = writer.write(rows,
                s -> JosaHints.applyTo(s.getLatestKo(), JosaHints.parse(s.getJosaHint()), kinds),
                reviewerId, activities.lazyPerProject(Activity.Kind.JOSA_FIX, reviewerId, Map.of()));
        return new ApplyResult(changed, 0);
    }

    @Transactional
    public void ignore(Long projectId, Long segmentId) {
        if (segmentId == null || segments.ignoreJosa(projectId, segmentId) == 0)
            throw new NotFoundException("행을 찾을 수 없습니다: " + segmentId);
    }

    @Transactional(readOnly = true)
    public Preview preview(Long projectId, String sheet) { return preview(targets(projectId, null, sheet)); }

    @Transactional(readOnly = true)
    public Preview preview(SheetScope scope) { return preview(targets(scope, null)); }

    private Preview preview(List<Segment> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, List<String>> examples = new LinkedHashMap<>();
        for (String k : KINDS) { counts.put(k, 0L); examples.put(k, new ArrayList<>()); }
        for (Segment s : rows) {
            for (JosaHint h : JosaHints.parse(s.getJosaHint())) {
                if (!counts.containsKey(h.kind())) continue;
                counts.merge(h.kind(), 1L, Long::sum);
                List<String> ex = examples.get(h.kind());
                String label = JosaHints.label(s.getLatestKo(), h);
                if (ex.size() < EXAMPLES && !ex.contains(label)) ex.add(label);
            }
        }
        return new Preview(counts, examples);
    }

    /** segmentIds가 있으면 그 행(이 프로젝트, 무시 안 한 것만), 없으면 sheet의 조사 확인 필터 대상 전부. */
    private List<Segment> targets(Long projectId, List<Long> segmentIds, String sheet) {
        if (segmentIds != null && !segmentIds.isEmpty())
            return segments.findByProjectIdAndSkippedFalseAndIdIn(projectId, segmentIds).stream()
                    .filter(s -> s.getJosaHint() != null && !s.isJosaIgnored()).toList();
        if (sheet == null || sheet.isBlank()) throw new IllegalArgumentException("시트를 정하세요.");
        return segments.findAll(SegmentSpecs.inSheet(projectId, sheet).and(SegmentSpecs.filter(RowFilter.JOSA, null)));
    }

    private List<Segment> targets(SheetScope scope, List<Long> segmentIds) {
        if (segmentIds != null && !segmentIds.isEmpty()) {
            Set<String> keys = new java.util.HashSet<>(scope.sheets().stream().map(SheetScope.SheetRef::key).toList());
            return segments.findByProjectIdInAndSkippedFalseAndIdIn(scope.projectIds(), segmentIds).stream()
                    .filter(s -> keys.contains(s.getProjectId() + "\u001e" + s.getSheetName()))
                    .filter(s -> s.getJosaHint() != null && !s.isJosaIgnored()).toList();
        }
        return segments.findAll(SegmentSpecs.inScope(scope).and(SegmentSpecs.filter(RowFilter.JOSA, null)));
    }
}
