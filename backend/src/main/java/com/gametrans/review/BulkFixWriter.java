package com.gametrans.review;

import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.text.GlossaryCheck;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 여러 행의 Fix를 한 트랜잭션으로 바꾸고 행마다 리비전을 남긴다.
 * 동일 원문 통일과 단어장 일괄 적용이 공유한다. 완료·제외·용어 행은 부르는 쪽에서 이미 걸러 온다.
 */
@Component
public class BulkFixWriter {
    private final RevisionService revisions;
    private final SegmentRepository segments;
    private final TermMissService termMiss;
    private final ReviewEvents events;

    public BulkFixWriter(RevisionService revisions, SegmentRepository segments, TermMissService termMiss, ReviewEvents events) {
        this.revisions = revisions;
        this.segments = segments;
        this.termMiss = termMiss;
        this.events = events;
    }

    /**
     * rows 각각의 새 Fix를 newText로 계산해 바뀐 행만 고친다.
     * 새 값이 null·공백이거나 지금 값과 같으면 건너뛴다. 실제로 바뀐 행 수를 돌려준다.
     */
    @Transactional
    public int write(List<Segment> rows, Function<Segment, String> newText, Long reviewerId) {
        return write(rows, newText, reviewerId, pid -> null);
    }

    /**
     * activityFor: 프로젝트 id → 이 쓰기를 매달 변경 내역 id(설계 2026-09-30). 실제로 바뀌는 행이 있을 때만 부르므로
     * 지연 생성 함수(ActivityService.lazyPerProject)를 넘기면 0행 프로젝트엔 activity가 안 생긴다.
     */
    @Transactional
    public int write(List<Segment> rows, Function<Segment, String> newText, Long reviewerId, Function<Long, Long> activityFor) {
        if (rows.isEmpty()) return 0;
        // 같은 행이 목록에 두 번 들어오면 두 번째 패스가 방금 쓴 값을 다시 리비전으로 남기게 되므로, id로 중복을 걷어내고 첫 번째만 남긴다.
        Map<Long, Segment> deduped = new LinkedHashMap<>();
        for (Segment s : rows) deduped.putIfAbsent(s.getId(), s);
        // 프로젝트(게임)마다 유효 단어장이 다르므로 term_miss 계산용 단어장을 프로젝트별로 한 번씩만 읽는다.
        Map<Long, Map<String, String>> glossaries = new HashMap<>();
        Set<Long> touched = new LinkedHashSet<>();
        int changed = 0;
        for (Segment s : deduped.values()) {
            String next = newText.apply(s);
            if (next == null) continue;
            String trimmed = next.trim();
            if (trimmed.isEmpty()) continue;
            // 지금 값은 리비전 이력을 다시 긁지 않고 트리거가 유지하는 latest_ko(읽기 전용)로 비교한다.
            if (trimmed.equals(s.getLatestKo())) continue;
            revisions.add(s.getId(), trimmed, TranslationRevision.Author.HUMAN, reviewerId, activityFor.apply(s.getProjectId()));
            Map<String, String> g = glossaries.computeIfAbsent(s.getProjectId(), termMiss::glossaryFor);
            segments.updateTermMiss(s.getId(), GlossaryCheck.format(GlossaryCheck.missing(s.getSrcText(), trimmed, g)));
            touched.add(s.getProjectId());
            changed++;
        }
        // 한 행씩 segment-updated를 쏘면 수백 건이 된다. 프로젝트마다 refresh 하나로 갈음한다.
        for (Long pid : touched) events.refresh(pid, reviewerId);
        return changed;
    }
}
