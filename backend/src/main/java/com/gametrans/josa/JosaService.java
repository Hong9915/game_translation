package com.gametrans.josa;

import com.gametrans.activity.Activity;
import com.gametrans.activity.ActivityService;
import com.gametrans.common.NotFoundException;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.review.BulkFixWriter;
import com.gametrans.workspace.SegmentSpecs;
import com.gametrans.workspace.WorkspaceDtos.RowFilter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 조사 제안 적용·무시·미리보기(설계 2026-10-07 §5). 확정·용어 행은 고치지 않는다. */
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
        Set<String> kinds = new LinkedHashSet<>(req.kinds() == null ? List.of() : req.kinds());
        kinds.retainAll(KINDS);
        if (kinds.isEmpty()) throw new IllegalArgumentException("고칠 종류를 하나 이상 고르세요.");
        List<Segment> rows = targets(projectId, req.segmentIds(), req.sheet());
        // 확정 행도 고친다(2026-10-07 사용자 결정) — 같은 원문 반영·단어장 일괄 적용과 달리 조사 고침은 받침만 맞추는 일이다.
        List<Segment> editable = rows.stream().filter(s -> !s.isTerm()).toList();
        int skipped = rows.size() - editable.size();
        int changed = writer.write(editable,
                s -> JosaHints.applyTo(s.getLatestKo(), JosaHints.parse(s.getJosaHint()), kinds),
                reviewerId, activities.lazyPerProject(Activity.Kind.JOSA_FIX, reviewerId, Map.of()));
        return new ApplyResult(changed, skipped);
    }

    @Transactional
    public void ignore(Long projectId, Long segmentId) {
        if (segmentId == null || segments.ignoreJosa(projectId, segmentId) == 0)
            throw new NotFoundException("행을 찾을 수 없습니다: " + segmentId);
    }

    @Transactional(readOnly = true)
    public Preview preview(Long projectId, String sheet) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, List<String>> examples = new LinkedHashMap<>();
        for (String k : KINDS) { counts.put(k, 0L); examples.put(k, new ArrayList<>()); }
        for (Segment s : targets(projectId, null, sheet)) {
            if (s.isTerm()) continue;
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
}
