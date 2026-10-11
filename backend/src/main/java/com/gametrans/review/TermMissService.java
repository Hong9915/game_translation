package com.gametrans.review;

import com.gametrans.common.NotFoundException;
import com.gametrans.glossary.EffectiveGlossary;
import com.gametrans.glossary.GlossaryEntry;
import com.gametrans.glossary.GlossaryPrompt;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.ProjectRepository;
import com.gametrans.text.GlossaryCheck;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 용어 불일치(term_miss) 계산에 쓰는 단어장과 시트 단위 재계산.
 * 구 용어 표(TermService.glossary) 대신 그 프로젝트가 속한 게임의 유효 단어장을 쓴다.
 */
@Service
public class TermMissService {
    private final ProjectRepository projects;
    private final EffectiveGlossary effective;
    private final SegmentRepository segments;
    private final ReviewEvents events;

    public TermMissService(ProjectRepository projects, EffectiveGlossary effective, SegmentRepository segments, ReviewEvents events) {
        this.projects = projects;
        this.effective = effective;
        this.segments = segments;
        this.events = events;
    }

    /** 그 프로젝트가 속한 게임. */
    public Long gameIdOf(Long projectId) {
        return projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + projectId))
                .getGame().getId();
    }

    /**
     * 프롬프트용: glossaryFor와 같은 항목인데 메모가 있으면 "번역어 (라벨: 메모)"로 붙어 있다(GlossaryPrompt.srcTerms).
     * 용어 누락 계산에는 쓰지 않는다 — 그건 glossaryFor.
     */
    public Map<String, String> promptTermsFor(Long projectId) {
        return GlossaryPrompt.srcTerms(effective.of(gameIdOf(projectId)));
    }

    /** 원문 → 들어가야 할 한국어. 유효 단어장 가운데 SRC이고 ko가 있는 항목만(메모 전용·KO 항목은 불일치 대상이 아니다). */
    public Map<String, String> glossaryFor(Long projectId) {
        Long gameId = gameIdOf(projectId);
        Map<String, String> out = new LinkedHashMap<>();
        for (GlossaryEntry e : effective.of(gameId)) {
            if (e.getKind() != GlossaryEntry.Kind.SRC || e.getKo() == null || e.getKo().isBlank()) continue;
            out.put(e.getFind(), e.getKo());
        }
        return out;
    }

    /**
     * 시트 하나의 용어 불일치를 지금 단어장으로 다시 계산한다. 값이 달라진 행만 쓰고 그 수를 돌려준다.
     * 대상은 저장(saveHuman)·용어 고치기가 term_miss를 다루는 행과 같다 — 제외(skipped) 행과 용어(is_term) 행은 뺀다.
     * 용어 행은 번역이 용어 자체라 저장할 때도 불일치를 다시 보지 않는다.
     * 지금 Fix는 리비전 이력을 긁지 않고 트리거가 유지하는 latest_ko(읽기 전용)로 본다.
     */
    @Transactional
    public int recompute(Long projectId, String sheet) {
        if (sheet == null || sheet.isBlank()) throw new IllegalArgumentException("다시 계산할 시트를 고르세요.");
        Map<String, String> glossary = glossaryFor(projectId);
        int changed = 0;
        for (Segment s : segments.findByProjectIdAndSheetNameAndSkippedFalseOrderByRowNoAscPairIndexAsc(projectId, sheet)) {
            if (s.isTerm()) continue;
            String termMiss = GlossaryCheck.format(GlossaryCheck.missing(s.getSrcText(), s.getLatestKo(), glossary));
            if (Objects.equals(termMiss, s.getTermMiss())) continue;
            segments.updateTermMiss(s.getId(), termMiss);
            changed++;
        }
        events.refresh(projectId, null);
        return changed;
    }
}
