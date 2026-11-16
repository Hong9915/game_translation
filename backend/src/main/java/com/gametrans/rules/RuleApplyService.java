package com.gametrans.rules;

import com.gametrans.activity.Activity;
import com.gametrans.activity.ActivityService;
import com.gametrans.common.NotFoundException;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.Project;
import com.gametrans.project.ProjectRepository;
import com.gametrans.review.BulkFixWriter;
import com.gametrans.rules.RuleDtos.*;
import com.gametrans.rules.RuleLearner.Row;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 문체 규칙 미리 보기·적용(설계 2026-11-13 §6). 적용은 체크한 행만, 미리 보기 뒤 바뀐 행은 건너뛴다. */
@Service
public class RuleApplyService {
    private final CorrectionRuleRepository rules;
    private final RuleExclusionRepository exclusions;
    private final RuleSource source;
    private final SegmentRepository segments;
    private final ProjectRepository projects;
    private final BulkFixWriter writer;
    private final ActivityService activities;

    public RuleApplyService(CorrectionRuleRepository rules, RuleExclusionRepository exclusions, RuleSource source,
                            SegmentRepository segments, ProjectRepository projects, BulkFixWriter writer, ActivityService activities) {
        this.rules = rules; this.exclusions = exclusions; this.source = source; this.segments = segments;
        this.projects = projects; this.writer = writer; this.activities = activities;
    }

    CorrectionRule rule(Long id) {
        CorrectionRule r = rules.findById(id).orElseThrow(() -> new NotFoundException("규칙을 찾을 수 없습니다: " + id));
        if (!"STYLE".equals(r.getType()) && !"PERIOD".equals(r.getType()))
            throw new IllegalArgumentException("문체 규칙과 마침표 규칙만 미리 보고 적용할 수 있습니다.");
        return r;
    }

    private static boolean period(CorrectionRule r) { return "PERIOD".equals(r.getType()); }

    @Transactional(readOnly = true)
    public Preview preview(Long ruleId, Long projectId) {
        CorrectionRule r = rule(ruleId);
        Set<Long> excl = exclusions.findSegmentIdsByRuleId(r.getId());
        List<Row> open = new ArrayList<>(), out = new ArrayList<>();
        for (Row row : source.rows(r.getGameId())) {
            if (projectId != null && row.projectId() != projectId) continue;
            if (period(r) ? PeriodStripper.candidate(row.ko()) : RuleLearner.styleCandidate(row, r.getTextKind())) (excl.contains(row.segmentId()) ? out : open).add(row);
        }
        boolean dropHa = !period(r) && RuleLearner.dropHaPreferred(source.edits(r.getGameId()));
        List<Row> all = new ArrayList<>(open); all.addAll(out);
        Map<Long, Segment> segs = new HashMap<>();
        for (Segment s : segments.findAllById(all.stream().map(Row::segmentId).toList())) segs.put(s.getId(), s);
        Map<Long, String> files = new HashMap<>();
        for (Project p : projects.findAllById(all.stream().map(Row::projectId).distinct().toList())) files.put(p.getId(), p.getFileName());
        java.util.function.BiFunction<Row, Boolean, PreviewRow> toRow = (row, excluded) -> {
            Segment s = segs.get(row.segmentId());
            StyleConverter.Converted cv = period(r) ? new StyleConverter.Converted(PeriodStripper.strip(row.ko()), true)
                    : StyleConverter.convert(row.ko(), dropHa);
            return new PreviewRow(row.segmentId(), row.projectId(), files.get(row.projectId()), s == null ? null : s.getSheetName(),
                    s == null ? 0 : s.getRowNo(), row.src(), row.ko(), cv.text(), cv.complete(),
                    !excluded && cv.complete() && !cv.text().equals(row.ko()));
        };
        Comparator<PreviewRow> order = Comparator.comparing((PreviewRow x) -> String.valueOf(x.fileName()))
                .thenComparing(x -> String.valueOf(x.sheet())).thenComparingInt(PreviewRow::rowNo);
        return new Preview(open.stream().map(x -> toRow.apply(x, false)).sorted(order).toList(),
                out.stream().map(x -> toRow.apply(x, true)).sorted(order).toList());
    }

    @Transactional
    public ApplyResult apply(Long ruleId, ApplyRequest req, Long reviewerId) {
        CorrectionRule r = rule(ruleId);
        Map<Long, Item> items = new LinkedHashMap<>();
        for (Item i : req.checked() == null ? List.<Item>of() : req.checked()) items.put(i.segmentId(), i);
        List<Segment> ok = new ArrayList<>();
        int skipped = 0;
        for (Segment s : segments.findAllById(items.keySet())) {
            Item i = items.get(s.getId());
            if (s.isConfirmed() || s.isSkipped() || !Objects.equals(s.getLatestKo(), i.before())) { skipped++; continue; }
            ok.add(s);
        }
        Map<String, Object> detail = new HashMap<>();
        detail.put("ruleId", r.getId()); detail.put("type", r.getType()); detail.put("textKind", String.valueOf(r.getTextKind()));
        int applied = writer.write(ok, s -> items.get(s.getId()).after(), reviewerId,
                activities.lazyPerProject(Activity.Kind.RULE_APPLY, reviewerId, detail));
        for (Long sid : req.unchecked() == null ? List.<Long>of() : req.unchecked()) {
            if (exclusions.existsByRuleIdAndSegmentId(r.getId(), sid)) continue;
            RuleExclusion x = new RuleExclusion();
            x.setRuleId(r.getId()); x.setSegmentId(sid); x.setDecidedBy(reviewerId); x.setDecidedAt(Instant.now());
            exclusions.save(x);
        }
        r.setStatus("APPROVED");
        r.setAppliedCount(r.getAppliedCount() + applied);
        r.setDecidedBy(reviewerId); r.setDecidedAt(Instant.now());
        rules.save(r);
        return new ApplyResult(applied, skipped);
    }
}
