package com.gametrans.term;

import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.review.TermMissService;
import com.gametrans.text.GlossaryCheck;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 확정 번역 뒤의 메모(골드 (의역: 게임 내 기본 화폐))를 번역어로 다루던 때 남은 데이터를 고친다.
 * 용어 행에 메모째 들어간 번역은 메모를 뗀 판을 새로 쌓고, 메모째 찾느라 붙은 용어 누락 표시는 다시 검사한다.
 * 메모가 남은 행만 건드리므로 한 번 고치고 나면 다음 기동부터는 할 일이 없다.
 */
@Component
public class GlossaryNoteCleanup {
    private static final Logger log = LoggerFactory.getLogger(GlossaryNoteCleanup.class);

    private final TermRepository terms;
    private final TermMissService termMiss;
    private final SegmentRepository segments;
    private final RevisionService revisions;

    public GlossaryNoteCleanup(TermRepository terms, TermMissService termMiss, SegmentRepository segments, RevisionService revisions) {
        this.terms = terms;
        this.termMiss = termMiss;
        this.segments = segments;
        this.revisions = revisions;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try { run(); } catch (RuntimeException e) { log.warn("용어 메모 정리 실패: {}", e.getMessage(), e); }
    }

    public void run() {
        Map<Long, Term> noted = terms.findAll().stream()
                .filter(t -> t.isConfirmed() && GlossaryCheck.noteOf(t.getConfirmedKo()) != null)
                .collect(Collectors.toMap(Term::getId, t -> t));
        if (noted.isEmpty()) return;

        int termRows = 0;
        List<Segment> linked = segments.findByTermIdIn(noted.keySet()).stream().filter(Segment::isTerm).toList();
        Map<Long, List<TranslationRevision>> history = revisions.bySegmentIds(linked.stream().map(Segment::getId).toList());
        for (Segment s : linked) {
            List<TranslationRevision> revs = history.getOrDefault(s.getId(), List.of());
            String full = noted.get(s.getTermId()).getConfirmedKo();
            // 사람이 그 뒤에 고친 행은 두고, 확정 번역이 메모째 들어가 있는 행만 고친다.
            if (revs.isEmpty() || !full.equals(revs.get(revs.size() - 1).getText())) continue;
            revisions.add(s.getId(), GlossaryCheck.koOf(full), TranslationRevision.Author.GLOSSARY);
            termRows++;
        }

        int rechecked = 0;
        for (Long projectId : noted.values().stream().map(Term::getProjectId).collect(Collectors.toSet())) {
            List<Segment> stale = segments.findTermMissed(projectId).stream()
                    .filter(s -> GlossaryCheck.parse(s.getTermMiss()).stream().anyMatch(m -> GlossaryCheck.noteOf(m) != null)).toList();
            if (stale.isEmpty()) continue;
            // 다시 검사는 다른 용어 불일치 계산과 같은 기준(게임의 유효 단어장)으로 한다
            Map<String, String> glossary = termMiss.glossaryFor(projectId);
            Map<Long, List<TranslationRevision>> revs = revisions.bySegmentIds(stale.stream().map(Segment::getId).toList());
            for (Segment s : stale) {
                List<TranslationRevision> r = revs.getOrDefault(s.getId(), List.of());
                String current = r.isEmpty() ? null : r.get(r.size() - 1).getText();
                String termMiss = GlossaryCheck.format(GlossaryCheck.missing(s.getSrcText(), current, glossary));
                if (!Objects.equals(termMiss, s.getTermMiss())) { segments.updateTermMiss(s.getId(), termMiss); rechecked++; }
            }
        }
        if (termRows > 0 || rechecked > 0) log.info("용어 메모 정리: 용어 행 {}개 번역에서 메모 제거, 용어 누락 {}행 다시 검사", termRows, rechecked);
    }
}
