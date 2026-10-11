package com.gametrans.term;

import com.gametrans.common.NotFoundException;
import com.gametrans.job.BatchExecutor;
import com.gametrans.job.JobContext;
import com.gametrans.job.JobDtos.JobDto;
import com.gametrans.job.JobRunner;
import com.gametrans.job.JobService;
import com.gametrans.job.TranslationJob;
import com.gametrans.llm.LlmClient;
import com.gametrans.memory.MemoryService;
import com.gametrans.llm.LlmClient.Candidate;
import com.gametrans.llm.LlmClient.CandidateSuggestion;
import com.gametrans.llm.LlmClient.TermExtraction;
import com.gametrans.llm.LlmClient.TermInput;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.term.TermDtos.TermDto;
import com.gametrans.term.TermDtos.TermStats;
import com.gametrans.term.TermDtos.TermUpdate;
import com.gametrans.term.TermDtos.TermsResponse;
import com.gametrans.text.GlossaryCheck;
import com.gametrans.translate.TranslationRevision;
import com.gametrans.translate.TranslationRevisionRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TermService {
    static final int EXTRACT_CHUNK = 200;
    static final int SUGGEST_CHUNK = 50;
    /** 반복 구절 후보 상한. */
    static final int MAX_FREQUENT = 300;
    /** IN 절 한 번에 넣는 id 수. Postgres 바인드 파라미터 상한(32767) 아래. */
    static final int IN_CHUNK = 10_000;

    private final TermRepository terms;
    private final SegmentRepository segments;
    private final TranslationRevisionRepository revisionRepo;
    private final JobService jobs;
    private final JobRunner runner;
    private final LlmClient llm;
    private final TransactionTemplate transactionTemplate;
    private final BatchExecutor batches;
    private final MemoryService memory;
    private final CandidateMiner miner;

    public TermService(TermRepository terms, SegmentRepository segments,
                       TranslationRevisionRepository revisionRepo, JobService jobs, JobRunner runner, LlmClient llm,
                       TransactionTemplate transactionTemplate,
                       BatchExecutor batches, MemoryService memory, CandidateMiner miner) {
        this.memory = memory;
        this.miner = miner;
        this.batches = batches;
        this.terms = terms;
        this.segments = segments;
        this.revisionRepo = revisionRepo;
        this.jobs = jobs;
        this.runner = runner;
        this.llm = llm;
        this.transactionTemplate = transactionTemplate;
    }

    // ---- 추출 ----

    public JobDto startExtract(Long projectId) {
        TranslationJob job = jobs.create(projectId, TranslationJob.Kind.EXTRACT_TERMS);
        runner.run(job.getId(), ctx -> extract(ctx, projectId));
        return jobs.get(job.getId());
    }

    void extract(JobContext ctx, Long projectId) {
        List<Segment> rows = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(projectId);
        List<String> srcs = rows.stream().map(Segment::getSrcText).distinct().toList();
        List<List<String>> chunks = chunk(srcs, EXTRACT_CHUNK);
        // GPT 전에 파일 전체에서 반복 명사구를 통계로 뽑아, 각 묶음에 나오는 것만 빈도와 함께 힌트로 준다.
        List<CandidateMiner.Phrase> frequent = miner.mine(srcs, MAX_FREQUENT);
        ctx.log("반복 구절 후보 " + frequent.size() + "개 (GPT 없이 빈도로 추림)");
        ctx.progress(0, chunks.size());
        ctx.log("묶음 " + chunks.size() + "개를 최대 " + batches.concurrency() + "개씩 동시에 GPT에 보냅니다");
        List<List<TermExtraction>> results = batches.map(ctx, chunks, (i, chunk) -> {
            ctx.log("묶음 " + (i + 1) + "/" + chunks.size() + " (" + chunk.size() + "행) 고유명사 추출 중…");
            Map<String, Integer> hints = new LinkedHashMap<>();
            for (CandidateMiner.Phrase p : frequent)
                if (chunk.stream().anyMatch(t -> t.contains(p.text()))) hints.put(p.text(), p.docFreq());
            return llm.extractTerms(chunk, hints);
        });
        if (ctx.stopRequested()) { ctx.log("중지됨"); return; }
        // 병합은 입력 순서대로 → 결과가 실행 순서에 좌우되지 않는다
        Map<String, Term.Type> found = new LinkedHashMap<>();
        int variants = 0;
        Set<String> stems = new LinkedHashSet<>();
        for (List<TermExtraction> extracted : results) {
            if (extracted == null) continue;
            for (TermExtraction e : extracted) {
                String raw = e.src() == null ? "" : e.src().trim();
                if (raw.isEmpty()) continue;
                // 11星英雄·12星英雄처럼 숫자만 다른 변형은 기본 단어 星英雄 하나로 접는다. 그 칸은 용어 행이 아니라 일반 문장으로 번역된다.
                String stem = GlossaryCheck.numericStem(raw);
                if (stem != null) { variants++; stems.add(stem); }
                final String src = stem != null ? stem : raw;
                if (found.containsKey(src)) continue;
                if (rows.stream().noneMatch(s -> s.getSrcText().contains(src))) continue;
                found.put(src, parseType(e.type()));
            }
        }
        if (variants > 0) ctx.log("숫자 변형 " + variants + "개는 기본 단어 " + stems.size() + "개로 접음");
        // 문장·문구 전체가 용어로 뽑히면 용어집을 거치지 않고 따로 번역돼 표현이 어긋난다 → 빼서 일반 번역 단계로 보낸다.
        Set<String> parts = new LinkedHashSet<>(found.keySet());
        parts.addAll(memory.allTerms(projectId).keySet());
        int extracted = found.size();
        found.entrySet().removeIf(e -> isWholeString(e.getKey(), e.getValue(), parts, rows));
        if (found.size() < extracted) ctx.log("문장형 " + (extracted - found.size()) + "개는 용어에서 빼고 번역 단계로 보냄");
        // 모든 묶음의 LLM 호출이 성공한 뒤에만 기존 미확정 용어를 지우고 새로 저장한다.
        // 도중에 예외가 나면(위 루프에서 던져짐) 여기 도달하지 않으므로 기존 용어가 보존된다.
        int[] added = {0, 0};
        // 같은 게임에서 이미 확정한 용어는 확정 입력란에 채워 두고, 저장 뒤 applyGameGlossary가 확정한다.
        Map<String, String> known = memory.knownTerms(projectId, found.keySet());
        transactionTemplate.executeWithoutResult(status -> {
            terms.deleteByProjectIdAndConfirmedFalse(projectId);
            for (Map.Entry<String, Term.Type> e : found.entrySet()) {
                if (terms.findByProjectIdAndSrcText(projectId, e.getKey()).isPresent()) continue;
                Term t = new Term();
                t.setProjectId(projectId);
                t.setSrcText(e.getKey());
                t.setType(e.getValue());
                t.setOccurrences((int) rows.stream().filter(s -> s.getSrcText().contains(e.getKey())).count());
                String ko = known.get(e.getKey());
                if (ko != null) { t.setConfirmedKo(ko); added[1]++; }
                terms.save(t);
                added[0]++;
            }
        });
        int applied = applyGameGlossary(projectId);
        String fromMemory = applied == 0 ? "" : " · 게임 용어집에서 " + applied + "개 자동 확정";
        ctx.log("고유명사 " + added[0] + "개 추출 (확정 " + terms.countByProjectIdAndConfirmedTrue(projectId) + "개)" + fromMemory);
    }

    // ---- 후보 추천 ----

    public JobDto startSuggest(Long projectId) {
        TranslationJob job = jobs.create(projectId, TranslationJob.Kind.SUGGEST_CANDIDATES);
        runner.run(job.getId(), ctx -> suggest(ctx, projectId));
        return jobs.get(job.getId());
    }

    /**
     * 짧은 용어를 먼저 추천받고, 그것을 품은 긴 용어는 짧은 용어의 번역을 고정값으로 넘겨서 추천받는다
     * (能量石 = 에너지 스톤이면 能量石选择 = 에너지 스톤 선택). 같은 게임에서 이미 확정한 용어도 고정값이다.
     */
    void suggest(JobContext ctx, Long projectId) {
        List<Term> pending = terms.findByProjectIdAndConfirmedFalseOrderByOccurrencesDescSrcTextAsc(projectId);
        Map<String, String> fixed = new LinkedHashMap<>(memory.allTerms(projectId));
        fixed.putAll(glossary(projectId));
        Set<String> shorter = new LinkedHashSet<>(fixed.keySet());
        for (Term t : pending) shorter.add(t.getSrcText());
        List<Term> atoms = new ArrayList<>(), compounds = new ArrayList<>();
        for (Term t : pending) (partsOf(t.getSrcText(), shorter).isEmpty() ? atoms : compounds).add(t);

        suggestPass(ctx, atoms, fixed, "");
        if (ctx.stopRequested()) { ctx.log("중지됨"); return; }
        for (Term t : atoms) if (t.getConfirmedKo() != null && !t.getConfirmedKo().isBlank()) fixed.putIfAbsent(t.getSrcText(), GlossaryCheck.koOf(t.getConfirmedKo()));
        if (!compounds.isEmpty()) ctx.log("짧은 용어를 품은 긴 용어 " + compounds.size() + "개는 그 번역을 고정해서 추천받습니다");
        suggestPass(ctx, compounds, fixed, "긴 용어 ");
        if (ctx.stopRequested()) { ctx.log("중지됨"); return; }
        ctx.log("후보 추천 완료: " + pending.size() + "개");
    }

    private void suggestPass(JobContext ctx, List<Term> pending, Map<String, String> fixed, String label) {
        List<List<Term>> chunks = chunk(pending, SUGGEST_CHUNK);
        if (chunks.isEmpty()) return;
        ctx.progress(0, chunks.size());
        batches.map(ctx, chunks, (i, chunk) -> {
            ctx.log(label + "묶음 " + (i + 1) + "/" + chunks.size() + " (" + chunk.size() + "개) 후보 추천 중…");
            Map<String, Term> bySrc = new LinkedHashMap<>();
            for (Term t : chunk) bySrc.put(t.getSrcText(), t);
            List<TermInput> inputs = chunk.stream().map(t -> new TermInput(t.getSrcText(), t.getType().name())).toList();
            Map<String, String> fixedParts = new LinkedHashMap<>();
            for (Term t : chunk) for (String part : partsOf(t.getSrcText(), fixed.keySet())) fixedParts.put(part, fixed.get(part));
            int unmatched = 0;
            for (CandidateSuggestion s : llm.suggestCandidates(inputs, fixedParts)) {
                Term t = matchTerm(bySrc, s.src());
                if (t == null) { unmatched++; continue; }
                if (s.candidates() == null || s.candidates().isEmpty()) continue;
                t.setCandidates(new ArrayList<>(s.candidates()));
                // 의역을 기본값으로 확정 입력란에 미리 채운다(확정은 아직). 사람이 직접 적은 값은 덮어쓰지 않는다.
                if (t.getConfirmedKo() == null || t.getConfirmedKo().isBlank()) t.setConfirmedKo(defaultCandidate(s.candidates()));
            }
            if (unmatched > 0) ctx.log(label + "묶음 " + (i + 1) + ": 응답의 원문 불일치 " + unmatched + "개는 버림");
            terms.saveAll(chunk);
            return chunk.size();
        });
    }

    /** src 안에 통째로 들어 있는 더 짧은 용어들(2자 이상). */
    static List<String> partsOf(String src, Collection<String> others) {
        return others.stream().filter(o -> o.length() >= 2 && o.length() < src.length() && src.contains(o)).toList();
    }

    private static final Pattern SENTENCE_MARKS = Pattern.compile("[，。？！；,?!;{}<>%]");

    /**
     * 용어가 아니라 문장·문구 전체인가. 문장부호나 자리표시자가 있거나, 다른 용어를 품은 일반 어휘(COMMON·OTHER)인데
     * 더 긴 문장 안에서는 한 번도 안 나오고 늘 행 전체로만 나오는 경우(能量石已满星). 고유명사는 행 전체여도 용어로 둔다.
     */
    static boolean isWholeString(String src, Term.Type type, Collection<String> others, List<Segment> rows) {
        if (SENTENCE_MARKS.matcher(src).find()) return true;
        if (type != Term.Type.COMMON && type != Term.Type.OTHER) return false;
        if (partsOf(src, others).isEmpty()) return false;
        return rows.stream().filter(s -> s.getSrcText().contains(src)).allMatch(s -> s.getSrcText().trim().equals(src));
    }

    /** 응답의 src를 용어에 맞춘다. 모델이 프롬프트 표기 그대로 "원문 (유형)"을 돌려주거나 앞뒤 공백을 붙이는 경우를 흡수한다. */
    static Term matchTerm(Map<String, Term> bySrc, String src) {
        if (src == null) return null;
        Term t = bySrc.get(src);
        if (t != null) return t;
        String trimmed = src.trim();
        t = bySrc.get(trimmed);
        if (t != null) return t;
        int paren = trimmed.lastIndexOf(" (");
        return paren > 0 && trimmed.endsWith(")") ? bySrc.get(trimmed.substring(0, paren).trim()) : null;
    }

    /** 의역(MEANING) 후보를 기본값으로, 없으면 첫 후보. */
    static String defaultCandidate(List<Candidate> candidates) {
        return candidates.stream().filter(c -> "MEANING".equalsIgnoreCase(c.style()) && c.text() != null && !c.text().isBlank())
                .map(Candidate::text).findFirst()
                .orElse(candidates.get(0).text());
    }

    // ---- 조회 ----

    public TermsResponse list(Long projectId, boolean unconfirmedOnly, String q) { return list(projectId, unconfirmedOnly, q, null, 0, 1000); }

    /** {@code type}이 null이면 모든 유형. 통계(stats)는 필터와 무관하게 프로젝트 전체 기준이다. */
    public TermsResponse list(Long projectId, boolean unconfirmedOnly, String q, Term.Type type, int page, int size) {
        List<Term> all = terms.findByProjectIdOrderByOccurrencesDescSrcTextAsc(projectId);
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<TermDto> out = all.stream()
                .filter(t -> !unconfirmedOnly || !t.isConfirmed())
                .filter(t -> type == null || t.getType() == type)
                .filter(t -> needle.isEmpty() || t.getSrcText().toLowerCase(Locale.ROOT).contains(needle)
                        || (t.getConfirmedKo() != null && t.getConfirmedKo().toLowerCase(Locale.ROOT).contains(needle)))
                .map(TermDto::of).toList();
        return new TermsResponse(stats(projectId, all), com.gametrans.config.Paging.slice(out, page, size), page, size, out.size());
    }

    private TermStats stats(Long projectId, List<Term> all) {
        List<Segment> rows = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(projectId);
        Set<String> srcs = new HashSet<>();
        for (Term t : all) srcs.add(t.getSrcText());
        long termRows = rows.stream().filter(s -> srcs.contains(s.getSrcText().trim())).count();
        long confirmed = all.stream().filter(Term::isConfirmed).count();
        return new TermStats(rows.size(), all.size(), termRows, rows.size() - termRows, confirmed);
    }

    public Map<String, String> glossary(Long projectId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Term t : terms.findByProjectIdAndConfirmedTrue(projectId)) out.put(t.getSrcText(), t.getConfirmedKo());
        return out;
    }

    public List<Term> confirmedTerms(Long projectId) { return terms.findByProjectIdAndConfirmedTrue(projectId); }

    // ---- 게임 용어집 자동 적용 ----

    /**
     * 같은 게임에서 이미 확정한 용어 가운데 이 파일 원문에 나오는 것을 사람 확정 없이 이 파일의 확정 용어로 만든다.
     * 이 파일에서 이미 확정한 용어는 건드리지 않고, 문장형은 추출과 같은 기준으로 뺀다. 새로 확정한 개수를 돌려준다.
     */
    public int applyGameGlossary(Long projectId) {
        List<com.gametrans.memory.GameTerm> game = memory.gameTerms(projectId);
        if (game.isEmpty()) return 0;
        List<Segment> rows = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(projectId);
        Set<String> all = new LinkedHashSet<>();
        for (com.gametrans.memory.GameTerm g : game) all.add(g.getSrcText());
        Integer applied = transactionTemplate.execute(status -> {
            List<Term> done = new ArrayList<>();
            for (com.gametrans.memory.GameTerm g : game) {
                String src = g.getSrcText();
                if (g.getKo() == null || g.getKo().isBlank()) continue;
                int occurrences = (int) rows.stream().filter(s -> s.getSrcText().contains(src)).count();
                if (occurrences == 0) continue;
                Term.Type type = parseType(g.getType());
                if (isWholeString(src, type, all, rows)) continue;
                Term t = terms.findByProjectIdAndSrcText(projectId, src).orElse(null);
                if (t != null && t.isConfirmed()) continue;
                if (t == null) {
                    t = new Term();
                    t.setProjectId(projectId);
                    t.setSrcText(src);
                    t.setType(type);
                    t.setOccurrences(occurrences);
                }
                t.setConfirmedKo(g.getKo());
                t.setConfirmed(true);
                done.add(terms.save(t));
            }
            linkAll(projectId, done, rows, false);
            return done.size();
        });
        return applied == null ? 0 : applied;
    }

    // ---- 직접 추가 ----

    /** GPT가 못 뽑은 용어를 사람이 넣는다. 이 파일에 없는 원문도 허용한다(등장 0회, 게임 공용 용어집용). */
    @Transactional
    public TermDto create(Long projectId, com.gametrans.term.TermDtos.TermCreate req) {
        String src = req.srcText() == null ? "" : req.srcText().trim();
        if (src.isEmpty()) throw new IllegalArgumentException("원문을 입력하세요.");
        if (terms.findByProjectIdAndSrcText(projectId, src).isPresent()) throw new IllegalArgumentException("이미 있는 용어입니다: " + src);
        List<Segment> rows = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(projectId);
        Term t = new Term();
        t.setProjectId(projectId);
        t.setSrcText(src);
        t.setType(parseType(req.type()));
        t.setOccurrences((int) rows.stream().filter(s -> s.getSrcText().contains(src)).count());
        String ko = req.confirmedKo() == null ? "" : req.confirmedKo().trim();
        if (!ko.isEmpty()) {
            t.setConfirmedKo(ko);
            t.setConfirmed(true);
        }
        t = terms.save(t);
        if (t.isConfirmed()) linkAll(projectId, List.of(t), rows, true);
        return TermDto.of(t);
    }

    // ---- 확정 ----

    @Transactional
    public TermDto update(Long termId, TermUpdate update) {
        Term t = terms.findById(termId).orElseThrow(() -> new NotFoundException("용어를 찾을 수 없습니다: " + termId));
        if (update.confirmedKo() != null) t.setConfirmedKo(update.confirmedKo().isBlank() ? null : update.confirmedKo().trim());
        List<Segment> rows = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(t.getProjectId());
        if (update.confirmed() != null) {
            if (update.confirmed()) {
                if (t.getConfirmedKo() == null) throw new IllegalArgumentException("확정 번역을 먼저 입력하세요.");
                t.setConfirmed(true);
                linkAll(t.getProjectId(), List.of(t), rows, true);
            } else {
                t.setConfirmed(false);
                unlink(t, rows);
            }
        } else if (t.isConfirmed()) {
            linkAll(t.getProjectId(), List.of(t), rows, true); // 확정 상태에서 텍스트만 바뀐 경우 새 GLOSSARY 리비전
        }
        return TermDto.of(terms.save(t));
    }

    /**
     * 미확정 용어 전부 확정. 확정 번역이 비어 있으면 후보 중 의역을 채워서 확정하고,
     * 후보조차 없는 용어만 미확정으로 남긴다.
     */
    @Transactional
    public TermsResponse confirmAll(Long projectId) {
        List<Segment> rows = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(projectId);
        List<Term> done = new ArrayList<>();
        for (Term t : terms.findByProjectIdAndConfirmedFalseOrderByOccurrencesDescSrcTextAsc(projectId)) {
            if ((t.getConfirmedKo() == null || t.getConfirmedKo().isBlank()) && t.getCandidates() != null && !t.getCandidates().isEmpty())
                t.setConfirmedKo(defaultCandidate(t.getCandidates()));
            if (t.getConfirmedKo() == null || t.getConfirmedKo().isBlank()) continue;
            t.setConfirmed(true);
            done.add(t);
        }
        terms.saveAll(done);
        linkAll(projectId, done, rows, true);
        return list(projectId, false, null);
    }

    @Transactional
    public TermsResponse confirmFilled(Long projectId) {
        List<Segment> rows = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(projectId);
        List<Term> done = new ArrayList<>();
        for (Term t : terms.findByProjectIdAndConfirmedFalseOrderByOccurrencesDescSrcTextAsc(projectId)) {
            if (t.getConfirmedKo() == null || t.getConfirmedKo().isBlank()) continue;
            t.setConfirmed(true);
            done.add(t);
        }
        terms.saveAll(done);
        linkAll(projectId, done, rows, true);
        return list(projectId, false, null);
    }

    /**
     * 시트를 새로 정규화한 직후, 이미 확정된 용어를 그 시트의 새 세그먼트에 다시 붙인다.
     * 확정할 때와 같은 규칙(matching)으로 맞추고 같은 GLOSSARY 리비전을 넣는다. 게임 공용 용어집에는
     * 다시 쓰지 않는다 — 확정할 때 이미 들어갔고, 여기서 바뀌는 것은 이 파일의 행 연결뿐이다.
     * 안 붙이면 그 행이 용어 행이 아닌 채로 GPT 번역 대상이 되고, 용어 행 수와 편집 경로도 어긋난다.
     */
    @Transactional
    public void relinkConfirmed(Long projectId, String sheetName) {
        List<Term> confirmed = terms.findByProjectIdAndConfirmedTrue(projectId);
        if (confirmed.isEmpty()) return;
        List<Segment> rows = segments.findByProjectIdAndSheetNameAndSkippedFalseOrderByRowNoAscPairIndexAsc(projectId, sheetName);
        linkAll(projectId, confirmed, rows, false);
    }

    /**
     * 확정 용어를 원문이 같은 행에 붙이고 확정 번역을 GLOSSARY 판으로 넣는다. remember면 게임 용어집에도 쓴다.
     * 용어 6천 개 × 행 5만 개 규모에서 용어마다 행을 훑고 행마다 쿼리를 날리면 제곱으로 느려진다 —
     * 쿼리 하나마다 Hibernate가 로드된 행 전부를 더티체크한다. 그래서 원문 인덱스 한 번, 현재 판 조회 한 번,
     * 쓰기 한 번으로 끝낸다. 결과는 용어를 하나씩 붙일 때와 같다: 현재 판이 이미 같은 번역이면 새 판을 만들지 않는다.
     */
    /** 번역이 바뀐 확정 용어를 용어 행에 다시 건다(GLOSSARY 리비전) — 게임 용어집 수정에서 rows를 한 번만 읽고 여러 용어에 쓰려고 연다. */
    public void relink(Long projectId, Collection<Term> confirmed, List<Segment> rows) { linkAll(projectId, confirmed, rows, true); }

    private void linkAll(Long projectId, Collection<Term> confirmed, List<Segment> rows, boolean remember) {
        if (confirmed.isEmpty()) return;
        if (remember) memory.rememberTerms(projectId, confirmed);
        Map<String, List<Segment>> bySrc = new HashMap<>();
        for (Segment s : rows) bySrc.computeIfAbsent(s.getSrcText().trim(), k -> new ArrayList<>()).add(s);
        List<Segment> touched = new ArrayList<>();
        Map<Long, String> wanted = new LinkedHashMap<>(); // 행 id → 넣을 번역
        for (Term t : confirmed) {
            // 확정 번역 뒤의 메모(의역: …)는 설명일 뿐이라 게임에 들어갈 번역문에는 넣지 않는다.
            String ko = GlossaryCheck.koOf(t.getConfirmedKo());
            for (Segment s : bySrc.getOrDefault(t.getSrcText(), List.of())) {
                s.setTerm(true);
                s.setTermId(t.getId());
                touched.add(s);
                wanted.put(s.getId(), ko);
            }
        }
        if (touched.isEmpty()) return;
        Map<Long, TranslationRevision> current = new HashMap<>();
        for (List<Long> ids : chunk(new ArrayList<>(wanted.keySet()), IN_CHUNK))
            for (TranslationRevision r : revisionRepo.findBySegmentIdInOrderBySegmentIdAscVersionAsc(ids)) current.put(r.getSegmentId(), r); // 판 오름차순이라 마지막이 현재 판
        List<TranslationRevision> added = new ArrayList<>();
        for (Map.Entry<Long, String> e : wanted.entrySet()) {
            TranslationRevision cur = current.get(e.getKey());
            if (cur != null && Objects.equals(cur.getText(), e.getValue())) continue;
            added.add(TranslationRevision.of(e.getKey(), cur == null ? 0 : cur.getVersion() + 1, e.getValue(), TranslationRevision.Author.GLOSSARY));
        }
        segments.saveAll(touched);
        revisionRepo.saveAll(added);
    }

    private void unlink(Term t, List<Segment> rows) {
        List<Long> ids = new ArrayList<>();
        for (Segment s : matching(t, rows)) {
            s.setTerm(false);
            s.setTermId(null);
            segments.save(s);
            ids.add(s.getId());
        }
        if (!ids.isEmpty()) revisionRepo.deleteBySegmentIdInAndAuthor(ids, TranslationRevision.Author.GLOSSARY);
    }

    private List<Segment> matching(Term t, List<Segment> rows) {
        return rows.stream().filter(s -> s.getSrcText().trim().equals(t.getSrcText())).toList();
    }

    static Term.Type parseType(String raw) {
        try { return Term.Type.valueOf(raw == null ? "OTHER" : raw.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return Term.Type.OTHER; }
    }

    static <T> List<List<T>> chunk(List<T> items, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) out.add(items.subList(i, Math.min(i + size, items.size())));
        return out;
    }
}
