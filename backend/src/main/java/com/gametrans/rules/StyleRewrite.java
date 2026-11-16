package com.gametrans.rules;

import com.gametrans.common.NotFoundException;
import com.gametrans.llm.LlmClient;
import com.gametrans.llm.LlmClient.FixTermsRow;
import com.gametrans.llm.LlmClient.TranslationResult;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.Project;
import com.gametrans.project.ProjectRepository;
import com.gametrans.rules.RuleDtos.PreviewRow;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 기계 변환이 안 되는 행을 GPT로 개조식으로(설계 2026-11-13 §4). 마디 끝 낱말 말고 다른 곳이 바뀐 결과, 태그·숫자가 바뀐 결과,
 * 서술식이 남은 결과는 버린다. 사람이 누를 때만 돈다.
 */
@Service
public class StyleRewrite {
    static final int MAX_ROWS = 200, BATCH = 40;
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern NUM = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern LAST_WORD = Pattern.compile("[가-힣]+(?=(?:<[^>]+>)*\\s*(?:[.!?。…~]+|$|\\n|\\\\n))");
    private static final Pattern FORMAL_LEFT = Pattern.compile("[가-힣]+(?:니다|십시오|하세요|한다|된다|있다|없다)(?=(?:<[^>]+>)*\\s*(?:[.!?。…~]+|$|\\n|\\\\n))");

    private final CorrectionRuleRepository rules;
    private final SegmentRepository segments;
    private final ProjectRepository projects;
    private final LlmClient llm;

    public StyleRewrite(CorrectionRuleRepository rules, SegmentRepository segments, ProjectRepository projects, LlmClient llm) {
        this.rules = rules; this.segments = segments; this.projects = projects; this.llm = llm;
    }

    private static List<String> all(Pattern p, String s) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** 마디 끝 낱말만 바뀌었고, 태그·숫자는 그대로, 서술식(의문 제외)이 남지 않았을 때만. */
    public static boolean acceptable(String before, String after) {
        if (before == null || after == null || after.isBlank() || before.equals(after)) return false;
        if (!all(TAG, before).equals(all(TAG, after))) return false;
        if (!all(NUM, before).equals(all(NUM, after))) return false;
        if (FORMAL_LEFT.matcher(after).find()) return false;
        String sb = LAST_WORD.matcher(before).replaceAll("").replaceAll("\\s+", " ").strip();
        String sa = LAST_WORD.matcher(after).replaceAll("").replaceAll("\\s+", " ").strip();
        return sb.equals(sa);
    }

    public List<PreviewRow> rewrite(Long ruleId, List<Long> segmentIds) {
        CorrectionRule r = rules.findById(ruleId).orElseThrow(() -> new NotFoundException("규칙을 찾을 수 없습니다: " + ruleId));
        if (!"STYLE".equals(r.getType())) throw new IllegalArgumentException("문체 규칙만 GPT로 고칠 수 있습니다.");
        if (segmentIds == null || segmentIds.isEmpty()) return List.of();
        if (segmentIds.size() > MAX_ROWS) throw new IllegalArgumentException("한 번에 " + MAX_ROWS + "행까지 고칠 수 있습니다.");
        List<Segment> segs = segments.findAllById(segmentIds).stream()
                .filter(s -> !s.isConfirmed() && !s.isSkipped() && s.getLatestKo() != null).toList();
        Map<Long, String> files = new HashMap<>();
        for (Project p : projects.findAllById(segs.stream().map(Segment::getProjectId).distinct().toList())) files.put(p.getId(), p.getFileName());
        Map<String, Segment> byIndex = new HashMap<>();
        for (Segment s : segs) byIndex.put(String.valueOf(s.getId()), s);
        List<PreviewRow> out = new ArrayList<>();
        for (int i = 0; i < segs.size(); i += BATCH) {
            List<FixTermsRow> batch = segs.subList(i, Math.min(segs.size(), i + BATCH)).stream()
                    .map(s -> new FixTermsRow(String.valueOf(s.getId()), s.getSrcText(), null, s.getLatestKo(), Map.of())).toList();
            for (TranslationResult t : llm.rewriteStyleBatch(batch)) {
                Segment s = byIndex.get(t.index());
                if (s == null || !acceptable(s.getLatestKo(), t.ko())) continue;
                out.add(new PreviewRow(s.getId(), s.getProjectId(), files.get(s.getProjectId()), s.getSheetName(), s.getRowNo(),
                        s.getSrcText(), s.getLatestKo(), t.ko(), true, true));
            }
        }
        return out;
    }
}
