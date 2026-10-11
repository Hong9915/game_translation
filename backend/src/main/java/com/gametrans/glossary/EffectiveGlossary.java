package com.gametrans.glossary;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 한 게임의 유효 단어장 계산만 떼어 낸 것. (kind, find)가 겹치면 게임 전용이 공유를 이긴다.
 * 저장소 하나에만 기대는 이유: GlossaryService는 GlossaryApplier → BulkFixWriter를 거치는데
 * BulkFixWriter가 용어 불일치 계산에 유효 단어장을 쓰므로, GlossaryService를 직접 받으면 빈 순환이 생긴다.
 */
@Component
public class EffectiveGlossary {
    private final GlossaryEntryRepository repo;

    public EffectiveGlossary(GlossaryEntryRepository repo) { this.repo = repo; }

    public List<GlossaryEntry> of(Long gameId) {
        Map<String, GlossaryEntry> byKey = new LinkedHashMap<>();
        for (GlossaryEntry e : repo.findByGameIdIsNull()) byKey.put(key(e), e);
        for (GlossaryEntry e : repo.findByGameId(gameId)) byKey.put(key(e), e);
        // 저장 순서(id)가 뒤바뀌면 일괄 적용에서 항목끼리 서로 물고 무는 순서가 흔들린다 — 항상 id 순으로 고정한다.
        return byKey.values().stream().sorted(Comparator.comparing(GlossaryEntry::getId)).toList();
    }

    private static String key(GlossaryEntry e) { return e.getKind() + "\u0000" + e.getFind(); }
}
