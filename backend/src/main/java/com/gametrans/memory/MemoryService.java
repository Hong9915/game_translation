package com.gametrans.memory;

import com.gametrans.llm.LlmClient.Reference;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.Project;
import com.gametrans.project.ProjectRepository;
import com.gametrans.translate.TranslationRevision;
import com.gametrans.translate.TranslationRevisionRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게임 이름으로 묶이는 문장 번역 메모리. 게임 이름이 없는 프로젝트는 읽지도 쓰지도 않는다.
 * 용어는 단어장(glossary)이 맡는다 — 옛 공용 용어집(game_term)은 2026-10-26에 없앴다.
 */
@Service
public class MemoryService {
    /** 참고 번역으로 넣는 최소 유사도와 행당·묶음당 개수. */
    static final double MIN_SCORE = 0.4;
    static final int PER_ROW = 2;
    static final int PER_BATCH = 30;

    private final MemoryEntryRepository entries;
    private final ProjectRepository projects;
    private final SegmentRepository segments;
    private final TranslationRevisionRepository revisions;

    public MemoryService(MemoryEntryRepository entries, ProjectRepository projects,
                         SegmentRepository segments, TranslationRevisionRepository revisions) {
        this.entries = entries;
        this.projects = projects;
        this.segments = segments;
        this.revisions = revisions;
    }

    /**
     * 게임 이름 없이 진행했던 프로젝트에 게임을 붙였을 때: 현재 번역문을 그 게임의 번역 메모리로 넣는다.
     * 사람이 고친 문장만 기존 메모리를 덮고, 나머지는 비어 있는 원문에만 들어간다.
     */
    @Transactional
    public void backfill(Long projectId) {
        if (gameOf(projectId).isEmpty()) return;
        List<Segment> segs = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(projectId);
        Map<Long, Segment> byId = new HashMap<>();
        for (Segment s : segs) byId.put(s.getId(), s);
        Map<Long, TranslationRevision> latest = new LinkedHashMap<>();
        for (TranslationRevision r : revisions.findBySegmentIdInOrderBySegmentIdAscVersionAsc(byId.keySet())) latest.put(r.getSegmentId(), r);
        Map<String, String> human = new LinkedHashMap<>();
        Map<String, String> others = new LinkedHashMap<>();
        for (TranslationRevision r : latest.values()) {
            Segment s = byId.get(r.getSegmentId());
            if (s == null || s.getSrcText() == null) continue;
            if (r.getAuthor() == TranslationRevision.Author.HUMAN || r.getAuthor() == TranslationRevision.Author.IMPORTED) human.put(s.getSrcText(), r.getText());
            else others.putIfAbsent(s.getSrcText(), r.getText());
        }
        rememberAll(projectId, human, MemoryEntry.Source.HUMAN, true);
        rememberAll(projectId, others, MemoryEntry.Source.EXPORT, false);
    }

    public Optional<String> gameOf(Long projectId) {
        return projects.findById(projectId).map(Project::getGameName).filter(g -> g != null && !g.isBlank());
    }

    // ---- 문장 메모리 ----

    @Transactional
    public void rememberText(Long projectId, String src, String ko, MemoryEntry.Source source) {
        rememberAll(projectId, Map.of(src == null ? "" : src, ko == null ? "" : ko), source, true);
    }

    /**
     * 여러 문장을 한꺼번에 저장한다. overwrite=false면 이미 있는 원문은 건드리지 않는다
     * (올린 파일의 기존 번역이 사람이 고친 문장을 덮지 않도록).
     */
    @Transactional
    public int rememberAll(Long projectId, Map<String, String> pairs, MemoryEntry.Source source, boolean overwrite) {
        Optional<String> game = gameOf(projectId);
        if (game.isEmpty() || pairs.isEmpty()) return 0;
        Map<String, String> clean = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null || e.getValue().isBlank()) continue;
            clean.put(e.getKey(), e.getValue().trim());
        }
        if (clean.isEmpty()) return 0;
        Map<String, MemoryEntry> existing = new HashMap<>();
        for (MemoryEntry m : entries.findByGameNameAndSrcTextIn(game.get(), clean.keySet())) existing.put(m.getSrcText(), m);
        List<MemoryEntry> toSave = new ArrayList<>();
        for (Map.Entry<String, String> e : clean.entrySet()) {
            MemoryEntry m = existing.get(e.getKey());
            if (m == null) {
                m = new MemoryEntry();
                m.setGameName(game.get());
                m.setSrcText(e.getKey());
            } else if (!overwrite || e.getValue().equals(m.getKo())) {
                continue;
            }
            m.setKo(e.getValue());
            m.setSource(source);
            m.setUpdatedAt(java.time.Instant.now());
            toSave.add(m);
        }
        entries.saveAll(toSave);
        return toSave.size();
    }

    /** 번역 작업 하나가 쓰는 조회용 스냅샷. 게임 이름이 없으면 비어 있다. */
    public Lookup lookup(Long projectId) {
        return gameOf(projectId).map(this::lookupGame).orElseGet(() -> new Lookup(new LinkedHashMap<>()));
    }

    public Lookup lookupGame(String gameName) {
        Map<String, String> all = new LinkedHashMap<>();
        for (MemoryEntry m : entries.findByGameName(gameName)) all.put(m.getSrcText(), m.getKo());
        return new Lookup(all);
    }

    public static final class Lookup {
        private final Map<String, String> exact;
        private final Similarity.Index index;

        Lookup(Map<String, String> all) {
            this.exact = all;
            this.index = new Similarity.Index(all);
        }

        public boolean isEmpty() { return exact.isEmpty(); }

        public String exact(String src) { return src == null ? null : exact.get(src); }

        /** 이 묶음의 원문들과 비슷한 메모리 문장. 행당 PER_ROW개, 묶음당 PER_BATCH개까지, 중복 제거. */
        public List<Reference> references(List<String> srcs) {
            if (exact.isEmpty()) return List.of();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            List<Reference> out = new ArrayList<>();
            for (String src : srcs) {
                for (Similarity.Match m : index.top(src, PER_ROW, MIN_SCORE)) {
                    if (m.src().equals(src) || !seen.add(m.src())) continue;
                    out.add(new Reference(m.src(), m.ko()));
                    if (out.size() >= PER_BATCH) return out;
                }
            }
            return out;
        }
    }
}
