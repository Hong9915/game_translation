package com.gametrans.translate;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RevisionService {
    private final TranslationRevisionRepository revisions;

    public RevisionService(TranslationRevisionRepository revisions) { this.revisions = revisions; }

    @Transactional
    public TranslationRevision add(Long segmentId, String text, TranslationRevision.Author author) {
        return add(segmentId, text, author, null);
    }

    @Transactional
    public TranslationRevision add(Long segmentId, String text, TranslationRevision.Author author, Long reviewerId) {
        return add(segmentId, text, author, reviewerId, null);
    }

    /** activityId가 있으면 그 변경 내역에 매단다(설계 2026-09-30). */
    @Transactional
    public TranslationRevision add(Long segmentId, String text, TranslationRevision.Author author, Long reviewerId, Long activityId) {
        int next = revisions.findTopBySegmentIdOrderByVersionDesc(segmentId).map(r -> r.getVersion() + 1).orElse(0);
        TranslationRevision r = TranslationRevision.of(segmentId, next, text, author);
        r.setReviewerId(reviewerId);
        r.setActivityId(activityId);
        return revisions.save(r);
    }

    public List<TranslationRevision> byActivityId(Long activityId) { return revisions.findByActivityIdOrderByIdAsc(activityId); }

    public List<TranslationRevision> saveAll(List<TranslationRevision> added) { return revisions.saveAll(added); }

    public Optional<TranslationRevision> current(Long segmentId) {
        return revisions.findTopBySegmentIdOrderByVersionDesc(segmentId);
    }

    public Map<Long, List<TranslationRevision>> bySegmentIds(Collection<Long> segmentIds) {
        Map<Long, List<TranslationRevision>> out = new LinkedHashMap<>();
        if (segmentIds.isEmpty()) return out;
        for (TranslationRevision r : revisions.findBySegmentIdInOrderBySegmentIdAscVersionAsc(segmentIds))
            out.computeIfAbsent(r.getSegmentId(), k -> new java.util.ArrayList<>()).add(r);
        return out;
    }
}
