package com.gametrans.memory;

import com.gametrans.llm.LlmClient.Reference;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.Project;
import com.gametrans.project.ProjectRepository;
import com.gametrans.term.Term;
import com.gametrans.term.TermRepository;
import com.gametrans.translate.TranslationRevision;
import com.gametrans.translate.TranslationRevisionRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게임 이름으로 묶이는 공용 용어집과 문장 번역 메모리. 게임 이름이 없는 프로젝트는 읽지도 쓰지도 않는다.
 */
@Service
public class MemoryService {
    /** 참고 번역으로 넣는 최소 유사도와 행당·묶음당 개수. */
    static final double MIN_SCORE = 0.4;
    static final int PER_ROW = 2;
    static final int PER_BATCH = 30;
    /** IN 절 한 번에 넣는 값 수. Postgres 바인드 파라미터 상한(32767) 아래. */
    static final int IN_CHUNK = 10_000;

    private final GameTermRepository gameTerms;
    private final MemoryEntryRepository entries;
    private final ProjectRepository projects;
    private final TermRepository terms;
    private final SegmentRepository segments;
    private final TranslationRevisionRepository revisions;

    public MemoryService(GameTermRepository gameTerms, MemoryEntryRepository entries, ProjectRepository projects,
                         TermRepository terms, SegmentRepository segments, TranslationRevisionRepository revisions) {
        this.gameTerms = gameTerms;
        this.entries = entries;
        this.projects = projects;
        this.terms = terms;
        this.segments = segments;
        this.revisions = revisions;
    }

    /**
     * 게임 이름 없이 진행했던 프로젝트에 게임을 붙였을 때: 이미 확정한 용어와 현재 번역문을 그 게임의 공유 자산으로 넣는다.
     * 사람이 고친 문장만 기존 메모리를 덮고, 나머지는 비어 있는 원문에만 들어간다.
     */
    @Transactional
    public void backfill(Long projectId) {
        if (gameOf(projectId).isEmpty()) return;
        for (Term t : terms.findByProjectIdAndConfirmedTrue(projectId))
            rememberTerm(projectId, t.getSrcText(), t.getType().name(), t.getConfirmedKo());
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
            if (r.getAuthor() == TranslationRevision.Author.HUMAN) human.put(s.getSrcText(), r.getText());
            else others.putIfAbsent(s.getSrcText(), r.getText());
        }
        rememberAll(projectId, human, MemoryEntry.Source.HUMAN, true);
        rememberAll(projectId, others, MemoryEntry.Source.EXPORT, false);
    }

    public Optional<String> gameOf(Long projectId) {
        return projects.findById(projectId).map(Project::getGameName).filter(g -> g != null && !g.isBlank());
    }

    // ---- 공용 용어집 ----

    @Transactional
    public void rememberTerm(Long projectId, String src, String type, String ko) {
        Optional<String> game = gameOf(projectId);
        if (game.isEmpty() || src == null || src.isBlank() || ko == null || ko.isBlank()) return;
        GameTerm t = gameTerms.findByGameNameAndSrcText(game.get(), src).orElseGet(GameTerm::new);
        t.setGameName(game.get());
        t.setSrcText(src);
        t.setType(type);
        t.setKo(ko.trim());
        t.setUpdatedAt(java.time.Instant.now());
        gameTerms.save(t);
    }

    /** rememberTerm을 여러 용어에 한 번에: 있는 항목을 한 쿼리로 찾고 저장도 한 번에 한다. 확정 번역이 빈 용어는 건너뛴다. */
    @Transactional
    public void rememberTerms(Long projectId, Collection<Term> confirmed) {
        Optional<String> game = gameOf(projectId);
        if (game.isEmpty()) return;
        List<Term> usable = confirmed.stream()
                .filter(t -> t.getSrcText() != null && !t.getSrcText().isBlank() && t.getConfirmedKo() != null && !t.getConfirmedKo().isBlank())
                .toList();
        if (usable.isEmpty()) return;
        Map<String, GameTerm> existing = new HashMap<>();
        for (int i = 0; i < usable.size(); i += IN_CHUNK) {
            List<String> srcs = usable.subList(i, Math.min(i + IN_CHUNK, usable.size())).stream().map(Term::getSrcText).toList();
            for (GameTerm g : gameTerms.findByGameNameAndSrcTextIn(game.get(), srcs)) existing.put(g.getSrcText(), g);
        }
        java.time.Instant now = java.time.Instant.now();
        List<GameTerm> out = new ArrayList<>();
        for (Term t : usable) {
            GameTerm g = existing.get(t.getSrcText());
            if (g == null) g = new GameTerm();
            g.setGameName(game.get());
            g.setSrcText(t.getSrcText());
            g.setType(t.getType().name());
            g.setKo(t.getConfirmedKo().trim());
            g.setUpdatedAt(now);
            out.add(g);
        }
        gameTerms.saveAll(out);
    }

    /** 이 게임의 공용 용어집에서 원문이 일치하는 항목 (원문 → 한국어). */
    public Map<String, String> knownTerms(Long projectId, Collection<String> srcs) {
        Optional<String> game = gameOf(projectId);
        Map<String, String> out = new LinkedHashMap<>();
        if (game.isEmpty() || srcs.isEmpty()) return out;
        for (GameTerm t : gameTerms.findByGameNameAndSrcTextIn(game.get(), srcs)) out.put(t.getSrcText(), t.getKo());
        return out;
    }

    /** 이 게임의 공용 용어집 전체 (원문 → 한국어). 게임이 없으면 비어 있다. */
    public Map<String, String> allTerms(Long projectId) {
        Map<String, String> out = new LinkedHashMap<>();
        gameOf(projectId).ifPresent(g -> { for (GameTerm t : gameTerms.findByGameName(g)) out.put(t.getSrcText(), t.getKo()); });
        return out;
    }

    /** 이 게임의 공용 용어집 항목 전체(종류 포함). 게임이 없으면 비어 있다. */
    public List<GameTerm> gameTerms(Long projectId) {
        return gameOf(projectId).map(gameTerms::findByGameName).orElseGet(List::of);
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

    /** 이 게임의 공용 용어집 중 주어진 문장에 원문이 그대로 나오는 항목 (원문 → 한국어). */
    public Map<String, String> termsIn(String gameName, String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) return out;
        for (GameTerm t : gameTerms.findByGameName(gameName)) if (text.contains(t.getSrcText())) out.put(t.getSrcText(), t.getKo());
        return out;
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
