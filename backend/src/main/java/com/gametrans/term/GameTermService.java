package com.gametrans.term;

import com.gametrans.common.NotFoundException;
import com.gametrans.game.Game;
import com.gametrans.game.GameDtos.GameTermDto;
import com.gametrans.game.GameService;
import com.gametrans.job.BatchExecutor;
import com.gametrans.job.JobContext;
import com.gametrans.job.JobDtos.JobDto;
import com.gametrans.job.JobRunner;
import com.gametrans.job.JobService;
import com.gametrans.job.TranslationJob;
import com.gametrans.llm.LlmClient;
import com.gametrans.llm.LlmClient.CandidateSuggestion;
import com.gametrans.llm.LlmClient.TermInput;
import com.gametrans.memory.GameTerm;
import com.gametrans.memory.GameTermRepository;
import com.gametrans.memory.MemoryEntry;
import com.gametrans.memory.MemoryEntryRepository;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.Project;
import com.gametrans.project.ProjectRepository;
import com.gametrans.review.TermMissService;
import com.gametrans.term.TermDtos.GameTermReplaceResult;
import com.gametrans.term.TermDtos.GameTermUpdateResult;
import com.gametrans.term.TermDtos.TermUpdate;
import com.gametrans.text.GlossaryCheck;
import com.gametrans.text.Josa;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 게임 공용 용어집을 직접 고친다. 고친 번역은 그 게임의 모든 파일(용어, 용어 행, 이미 번역된 문장)과 번역 메모리에 퍼진다. */
@Service
public class GameTermService {
    static final int IN_CHUNK = 10_000;
    private final GameService games;
    private final GameTermRepository gameTerms;
    private final TermRepository terms;
    private final TermService termService;
    private final ProjectRepository projects;
    private final SegmentRepository segments;
    private final RevisionService revisions;
    private final MemoryEntryRepository entries;
    private final JobService jobs;
    private final JobRunner runner;
    private final LlmClient llm;
    private final BatchExecutor batches;
    private final TransactionTemplate transactionTemplate;
    private final TermMissService termMiss;

    public GameTermService(GameService games, GameTermRepository gameTerms, TermRepository terms, TermService termService,
                           ProjectRepository projects, SegmentRepository segments, RevisionService revisions, MemoryEntryRepository entries,
                           JobService jobs, JobRunner runner, LlmClient llm, BatchExecutor batches, TransactionTemplate transactionTemplate,
                           TermMissService termMiss) {
        this.termMiss = termMiss;
        this.games = games;
        this.gameTerms = gameTerms;
        this.terms = terms;
        this.termService = termService;
        this.projects = projects;
        this.segments = segments;
        this.revisions = revisions;
        this.entries = entries;
        this.jobs = jobs;
        this.runner = runner;
        this.llm = llm;
        this.batches = batches;
        this.transactionTemplate = transactionTemplate;
    }

    @Transactional
    public GameTermUpdateResult update(Long gameId, Long gameTermId, String koParam) {
        Game game = games.get(gameId);
        GameTerm gt = gameTerms.findById(gameTermId).filter(t -> game.getName().equals(t.getGameName()))
                .orElseThrow(() -> new NotFoundException("용어를 찾을 수 없습니다: " + gameTermId));
        if (koParam == null || koParam.isBlank()) throw new IllegalArgumentException("확정 번역을 입력하세요.");
        String newKo = koParam.trim();
        if (newKo.equals(gt.getKo())) return new GameTermUpdateResult(dto(gt), 0, 0, 0);
        int[] n = apply(game, List.of(new Change(gt, gt.getKo(), newKo)));
        return new GameTermUpdateResult(dto(gt), n[0], n[1], n[2]);
    }

    /**
     * 일괄 치환. 원문에 src가 든(비우면 모든) 용어 가운데 번역에 from이 든 것을 한꺼번에 고친다 — 용어 하나를 고칠 때와 같은 방식으로
     * 파일 용어·문장·메모리에 퍼지되, 파일마다 문장을 한 번만 읽는다(용어마다 되풀이하면 문장 5만 행짜리 파일에서 몇 분이 걸린다).
     */
    @Transactional
    public GameTermReplaceResult replace(Long gameId, String srcParam, String fromParam, String toParam) {
        Game game = games.get(gameId);
        if (fromParam == null || fromParam.isBlank()) throw new IllegalArgumentException("바꿀 말을 입력하세요.");
        if (toParam == null || toParam.isBlank()) throw new IllegalArgumentException("새 말을 입력하세요.");
        String src = srcParam == null ? "" : srcParam.trim(), from = fromParam.trim(), to = toParam.trim();
        if (from.equals(to)) return new GameTermReplaceResult(0, 0, 0, 0);
        List<Change> changes = gameTerms.findByGameName(game.getName()).stream()
                .filter(t -> (src.isEmpty() || t.getSrcText().contains(src)) && GlossaryCheck.koOf(t.getKo()).contains(from))
                .map(t -> new Change(t, t.getKo(), t.getKo().replace(from, to)))
                .toList();
        int[] n = apply(game, changes);
        return new GameTermReplaceResult(changes.size(), n[0], n[1], n[2]);
    }

    // ---- 숫자 변형 정리 ----

    /** 숫자만 다른 변형 용어(11星英雄·12星英雄)를 기본 단어(星英雄) 하나로 접는 게임 단위 작업. */
    public JobDto startCollapse(Long gameId) {
        Game game = games.get(gameId);
        TranslationJob job = jobs.createForGame(gameId, TranslationJob.Kind.COLLAPSE_TERMS);
        runner.run(job.getId(), ctx -> collapse(ctx, game));
        return jobs.get(job.getId());
    }

    /**
     * 1) 기본 단어가 용어집에 없는 그룹은 GPT 후보 추천으로 번역을 만들어 바로 게임 용어로 저장한다(그 안에 든 짧은 용어는 고정값).
     * 2) 기본 단어가 있는(또는 방금 만든) 그룹의 변형을 게임 용어집·모든 파일의 용어에서 지운다. 그 행의 번역은 그대로 두고 일반 행으로 돌린다.
     * 3) 변형이 있던 파일에 게임 용어집을 적용해 새 기본 단어를 그 파일의 확정 용어로 만들고,
     * 4) 지운 변형이 든 문장 행의 용어 불일치를 최종 용어집으로 다시 계산한다.
     */
    void collapse(JobContext ctx, Game game) {
        List<GameTerm> all = gameTerms.findByGameName(game.getName());
        Map<String, GameTerm> bySrc = new LinkedHashMap<>();
        for (GameTerm t : all) bySrc.put(t.getSrcText(), t);
        Map<String, List<GameTerm>> groups = new LinkedHashMap<>();   // 기본 단어 → 변형들
        for (GameTerm t : all) {
            String stem = GlossaryCheck.numericStem(t.getSrcText());
            if (stem != null) groups.computeIfAbsent(stem, k -> new ArrayList<>()).add(t);
        }
        if (groups.isEmpty()) { ctx.log("숫자 변형이 없습니다"); return; }
        // 기본 단어가 용어집에 없는데 변형이 하나뿐이면 접지 않는다: 그 하나가 진짜 이름일 수 있는데(7级黑暗城堡)
        // GPT가 만든 기본 단어로 바꿔 치우면 되돌릴 수 없다. 변형이 둘 이상이면 같은 계열이라는 증거로 본다.
        List<String> skipped = new ArrayList<>();
        groups.entrySet().removeIf(e -> {
            if (e.getValue().size() >= 2 || bySrc.containsKey(e.getKey())) return false;
            skipped.add(e.getValue().get(0).getSrcText() + " (기본 단어 " + e.getKey() + "이 용어집에 없고 변형이 하나뿐)");
            return true;
        });
        if (!skipped.isEmpty()) ctx.log("건너뜀: " + String.join(" · ", skipped));
        if (groups.isEmpty()) { ctx.log("접을 숫자 변형이 없습니다"); return; }
        int variants = groups.values().stream().mapToInt(List::size).sum();
        List<String> missing = groups.keySet().stream().filter(s -> !bySrc.containsKey(s)).toList();
        ctx.log("숫자 변형 " + variants + "개 → 기본 단어 " + groups.size() + "개 (이미 있음 " + (groups.size() - missing.size()) + " · 새로 만듦 " + missing.size() + ")");

        // 1) 없는 기본 단어의 번역을 GPT에게 받아 바로 확정한다
        Map<String, String> fixed = new LinkedHashMap<>();
        for (GameTerm t : all) fixed.put(t.getSrcText(), GlossaryCheck.koOf(t.getKo()));
        List<List<String>> chunks = TermService.chunk(missing, TermService.SUGGEST_CHUNK);
        ctx.progress(0, chunks.size());
        if (!chunks.isEmpty()) ctx.log("기본 단어 " + missing.size() + "개의 번역을 묶음 " + chunks.size() + "개로 GPT에 보냅니다");
        List<Map<String, String>> suggested = batches.map(ctx, chunks, (i, chunk) -> {
            List<TermInput> inputs = chunk.stream().map(s -> new TermInput(s, typeOf(groups.get(s)))).toList();
            Map<String, String> fixedParts = new LinkedHashMap<>();
            for (String s : chunk) for (String part : TermService.partsOf(s, fixed.keySet())) fixedParts.put(part, fixed.get(part));
            Set<String> wanted = new LinkedHashSet<>(chunk);
            Map<String, String> out = new LinkedHashMap<>();
            for (CandidateSuggestion s : llm.suggestCandidates(inputs, fixedParts)) {
                String src = normalizeSrc(s.src());
                if (src == null || !wanted.contains(src) || s.candidates() == null || s.candidates().isEmpty()) continue;
                out.put(src, TermService.defaultCandidate(s.candidates()));
            }
            return out;
        });
        if (ctx.stopRequested()) { ctx.log("중지됨"); return; }
        Map<String, String> created = new LinkedHashMap<>();
        for (Map<String, String> m : suggested) if (m != null) created.putAll(m);
        Instant now = Instant.now();
        List<GameTerm> newTerms = new ArrayList<>();
        for (Map.Entry<String, String> e : created.entrySet()) {
            GameTerm g = new GameTerm();
            g.setGameName(game.getName());
            g.setSrcText(e.getKey());
            g.setType(typeOf(groups.get(e.getKey())));
            g.setKo(e.getValue());
            g.setUpdatedAt(now);
            newTerms.add(g);
        }
        gameTerms.saveAll(newTerms);
        if (!created.isEmpty())
            ctx.log("새 용어: " + created.entrySet().stream().map(e -> e.getKey() + " = " + e.getValue()).collect(Collectors.joining(" · ")));
        List<String> failed = missing.stream().filter(s -> !created.containsKey(s)).toList();

        // 2) 기본 단어가 있는 그룹의 변형을 지운다 (그룹 단위 트랜잭션). 그룹마다 커밋 직후 바로 로그를 남겨서,
        // 뒤 그룹에서 예외가 나도(작업이 FAILED로 끝나도) 이미 커밋된 앞 그룹의 삭제가 로그에서 빠지지 않는다.
        int termCount = 0, rowCount = 0;
        Set<Long> touchedProjects = new LinkedHashSet<>();   // 변형이 하나라도 있던 파일
        List<String> deleted = new ArrayList<>();            // 지운 변형 원문 전부
        for (Map.Entry<String, List<GameTerm>> g : groups.entrySet()) {
            if (failed.contains(g.getKey())) continue;
            Deleted n = transactionTemplate.execute(status -> deleteVariants(game, g.getKey(), g.getValue()));
            termCount += n.terms();
            rowCount += n.rows();
            touchedProjects.addAll(n.projectIds());
            g.getValue().forEach(t -> deleted.add(t.getSrcText()));
            String srcs = g.getValue().stream().map(GameTerm::getSrcText).collect(Collectors.joining(", "));
            ctx.log("지움: " + srcs + " → " + g.getKey());
        }

        // 3) 변형을 지운 파일에 게임 용어집을 적용한다. 새로 만든 기본 단어의 프로젝트 용어가 있어야
        // 검수의 용어 고치기가 아래에서 붙이는 용어 불일치 표시를 실제로 고칠 수 있다.
        int applied = 0;
        for (Long projectId : touchedProjects) applied += termService.applyGameGlossary(projectId);
        if (!touchedProjects.isEmpty()) ctx.log("파일 " + touchedProjects.size() + "개에 게임 용어집 적용 (용어 " + applied + "개 자동 확정)");

        // 4) 지운 변형이 든 문장(击败12星英雄可进入1级钻石秘境)의 용어 불일치를 최종 용어집으로 다시 계산한다.
        int missCount = 0;
        for (Long projectId : touchedProjects) {
            Integer n = transactionTemplate.execute(status -> recheckSentences(projectId, deleted));
            missCount += n == null ? 0 : n;
        }
        ctx.log("파일 반영: 용어 " + termCount + "개 삭제 · 문장 " + rowCount + "행을 일반 행으로 (그중 용어 불일치 " + missCount + "행 → 검수에서 용어 고치기)");
        if (!failed.isEmpty()) ctx.log("만들지 못함: " + String.join(", ", failed) + " — GPT가 번역 후보를 주지 않아 그 변형은 그대로 둡니다");
    }

    /**
     * 지운 변형이 원문에 든 일반 행(용어 행이 아닌 행)의 용어 불일치를 게임의 유효 단어장으로 다시 계산한다.
     * 변형에 걸려 있던 행은 물론, 변형을 문장 안에 품은 행(击败12星英雄可进入1级钻石秘境)도 정리 전에 계산된 표시를 들고 있어서다.
     * 돌려주는 값은 불일치가 남은 행 수.
     */
    private int recheckSentences(Long projectId, List<String> deleted) {
        Map<String, String> glossary = termMiss.glossaryFor(projectId);
        List<Segment> rows = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(projectId).stream()
                .filter(s -> !s.isTerm() && deleted.stream().anyMatch(v -> s.getSrcText().contains(v)))
                .toList();
        if (rows.isEmpty()) return 0;
        Map<Long, List<TranslationRevision>> history = new HashMap<>();
        for (List<Long> ids : chunk(rows.stream().map(Segment::getId).toList())) history.putAll(revisions.bySegmentIds(ids));
        int missCount = 0;
        for (Segment s : rows) {
            List<TranslationRevision> revs = history.getOrDefault(s.getId(), List.of());
            String text = revs.isEmpty() ? null : revs.get(revs.size() - 1).getText();
            List<String> miss = GlossaryCheck.missing(s.getSrcText(), text, glossary);
            s.setTermMiss(GlossaryCheck.format(miss));
            if (!miss.isEmpty()) missCount++;
        }
        segments.saveAll(rows);
        return missCount;
    }

    /** 한 그룹을 지운 결과: [파일 용어 수, 일반 행으로 돌린 문장 수, 변형이 있던 파일들]. */
    private record Deleted(int terms, int rows, Set<Long> projectIds) {}

    /**
     * 한 그룹의 변형을 게임 용어집과 모든 파일의 용어에서 지운다. 변형에 걸려 있던 행은 번역을 그대로 둔 채 일반 행으로 돌리고,
     * 게임의 유효 단어장으로 용어 누락을 다시 계산한다(최종 계산은 뒤의 recheckSentences가 맡는다).
     */
    private Deleted deleteVariants(Game game, String stem, List<GameTerm> variants) {
        List<String> srcs = variants.stream().map(GameTerm::getSrcText).toList();
        gameTerms.deleteAll(variants);
        int termCount = 0, rowCount = 0;
        Set<Long> touched = new LinkedHashSet<>();
        for (Project p : projects.findByGameIdOrderByUploadedAtDesc(game.getId())) {
            List<Term> victims = terms.findByProjectIdAndSrcTextIn(p.getId(), srcs);
            if (victims.isEmpty()) continue;
            touched.add(p.getId());
            Map<String, String> glossary = termMiss.glossaryFor(p.getId());
            List<Segment> rows = segments.findByTermIdIn(victims.stream().map(Term::getId).toList());
            Map<Long, List<TranslationRevision>> history = revisions.bySegmentIds(rows.stream().map(Segment::getId).toList());
            for (Segment s : rows) {
                s.setTerm(false);
                s.setTermId(null);
                List<TranslationRevision> revs = history.getOrDefault(s.getId(), List.of());
                String text = revs.isEmpty() ? null : revs.get(revs.size() - 1).getText();
                s.setTermMiss(GlossaryCheck.format(GlossaryCheck.missing(s.getSrcText(), text, glossary)));
                rowCount++;
            }
            segments.saveAll(rows);
            terms.deleteAll(victims);
            termCount += victims.size();
        }
        return new Deleted(termCount, rowCount, touched);
    }

    /** 변형들 중 가장 많은 유형. 동률이면 먼저 나온 것. */
    static String typeOf(List<GameTerm> variants) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (GameTerm t : variants) counts.merge(t.getType(), 1L, Long::sum);
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("OTHER");
    }

    /** 응답의 src를 요청한 원문에 맞춘다. 모델이 "원문 (유형)"으로 돌려주거나 공백을 붙이는 경우를 흡수한다(TermService.matchTerm과 같은 규칙). */
    static String normalizeSrc(String src) {
        if (src == null) return null;
        String trimmed = src.trim();
        int paren = trimmed.lastIndexOf(" (");
        return paren > 0 && trimmed.endsWith(")") ? trimmed.substring(0, paren).trim() : trimmed;
    }

    /** 게임 용어 하나의 번역 변경(옛 번역 → 새 번역). */
    private record Change(GameTerm term, String oldKo, String newKo) {
        String src() { return term.getSrcText(); }
    }

    /**
     * 바뀐 용어들을 게임 용어집·모든 파일·번역 메모리에 적용한다. 돌려주는 값은 [파일 용어 수, 문장 수, 메모리 수].
     * 긴 용어부터 적용해서 문장은 가장 구체적인 용어의 번역으로 먼저 바뀌고, 짧은 용어(英雄)는 긴 용어 밖에 나온 자리만 맡는다.
     */
    private int[] apply(Game game, List<Change> changesParam) {
        List<Change> changes = new ArrayList<>(changesParam);
        changes.sort(Comparator.comparingInt((Change c) -> c.src().length()).reversed());
        Instant now = Instant.now();
        for (Change c : changes) {
            c.term().setKo(c.newKo());
            c.term().setUpdatedAt(now);
        }
        gameTerms.saveAll(changes.stream().map(Change::term).toList());
        List<String> srcs = changes.stream().map(Change::src).toList();

        int termCount = 0, sentenceCount = 0;
        for (Project p : projects.findByGameIdOrderByUploadedAtDesc(game.getId())) {
            List<Segment> rows = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(p.getId());
            // 확정된 용어는 물론, 옛 번역이 미리 채워져 있던 미확정 용어도 같이 바꾼다. 용어 행의 번역은 TermService가 다시 건다.
            Map<String, Term> bySrc = new HashMap<>();
            for (List<String> chunk : chunk(srcs)) for (Term t : terms.findByProjectIdAndSrcTextIn(p.getId(), chunk)) bySrc.put(t.getSrcText(), t);
            List<Term> confirmed = new ArrayList<>();
            for (Change c : changes) {
                Term t = bySrc.get(c.src());
                if (t == null || !(t.isConfirmed() || c.oldKo().equals(t.getConfirmedKo()))) continue;
                t.setConfirmedKo(c.newKo());
                terms.save(t);
                if (t.isConfirmed()) confirmed.add(t);
                termCount++;
            }
            termService.relink(p.getId(), confirmed, rows);
            sentenceCount += rewriteSentences(p.getId(), changes, rows);
        }

        int memoryCount = 0;
        List<MemoryEntry> touched = new ArrayList<>();
        for (MemoryEntry m : entries.findByGameName(game.getName())) {
            String text = m.getKo();
            boolean changed = false;
            for (Change c : changes) {
                if (!m.getSrcText().contains(c.src())) continue;
                String replaced = replace(text, c.oldKo(), c.newKo());
                if (replaced == null) continue;
                text = replaced;
                changed = true;
            }
            if (!changed) continue;
            m.setKo(text);
            m.setUpdatedAt(now);
            touched.add(m);
            memoryCount++;
        }
        entries.saveAll(touched);
        return new int[] {termCount, sentenceCount, memoryCount};
    }

    /**
     * 바뀐 용어가 실제로 적용되는 문장에서 옛 번역어를 새 번역어로 바꾸고(GLOSSARY 리비전), 용어 누락을 다시 검사한다.
     * 적용 여부는 용어 하나를 고칠 때와 같은 기준이다: 이 파일의 용어집에 그 용어를 하나 얹어서 본다. 이 파일에 없는 게임 용어는 다른 게임 용어를
     * 가리지 않으므로, 英雄数量이 이 파일 용어가 아니면 그 문장의 英雄에는 英雄의 번역이 그대로 적용된다.
     */
    private int rewriteSentences(Long projectId, List<Change> changes, List<Segment> rows) {
        Map<String, String> glossary = termService.glossary(projectId);   // 위에서 고친 파일 용어가 반영된 상태 — 옛 번역어를 어디서 바꿀지 정하는 데만 쓴다
        Map<String, String> check = termMiss.glossaryFor(projectId);        // 용어 누락은 게임의 유효 단어장으로 매긴다
        Map<String, Map<String, String>> views = new HashMap<>();   // 변경 용어 원문 → 그 용어를 얹은 용어집
        for (Change c : changes) {
            Map<String, String> view = glossary;
            if (!glossary.containsKey(c.src())) { view = new LinkedHashMap<>(glossary); view.put(c.src(), c.newKo()); }
            views.put(c.src(), view);
        }
        List<Segment> candidates = rows.stream()
                .filter(s -> !s.isTerm() && changes.stream().anyMatch(c -> s.getSrcText().contains(c.src())))
                .toList();
        Map<Long, List<TranslationRevision>> history = new HashMap<>();
        for (List<Long> ids : chunk(candidates.stream().map(Segment::getId).toList())) history.putAll(revisions.bySegmentIds(ids));
        List<TranslationRevision> added = new ArrayList<>();
        List<Segment> touched = new ArrayList<>();
        for (Segment s : candidates) {
            List<TranslationRevision> revs = history.getOrDefault(s.getId(), List.of());
            if (revs.isEmpty()) continue;
            String text = revs.get(revs.size() - 1).getText();
            boolean changed = false;
            for (Change c : changes) {
                if (!s.getSrcText().contains(c.src()) || !GlossaryCheck.applicable(s.getSrcText(), views.get(c.src())).containsKey(c.src())) continue;
                String replaced = replace(text, GlossaryCheck.koOf(c.oldKo()), GlossaryCheck.koOf(c.newKo()));
                if (replaced == null) continue;
                text = replaced;
                changed = true;
            }
            if (changed) added.add(TranslationRevision.of(s.getId(), revs.get(revs.size() - 1).getVersion() + 1, text, TranslationRevision.Author.GLOSSARY));
            s.setTermMiss(GlossaryCheck.format(GlossaryCheck.missing(s.getSrcText(), text, check)));
            touched.add(s);
        }
        segments.saveAll(touched);
        revisions.saveAll(added);
        return added.size();
    }

    private static <T> List<List<T>> chunk(List<T> items) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += IN_CHUNK) out.add(items.subList(i, Math.min(i + IN_CHUNK, items.size())));
        return out;
    }

    /** 바꿀 게 없으면 null. 새 번역어가 옛 번역어를 품고 있고 이미 들어가 있으면(상점 → 상점가) 두 번 바꾸지 않는다. */
    static String replace(String text, String oldKo, String newKo) {
        if (text == null || oldKo == null || oldKo.isBlank() || !text.contains(oldKo)) return null;
        if (newKo.contains(oldKo) && text.contains(newKo)) return null;
        return Josa.replaceAdjusting(text, oldKo, newKo);
    }

    private static GameTermDto dto(GameTerm t) {
        return new GameTermDto(t.getId(), t.getSrcText(), t.getType(), t.getKo(), t.getUpdatedAt());
    }
}
