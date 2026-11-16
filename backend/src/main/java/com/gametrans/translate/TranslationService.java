package com.gametrans.translate;

import com.gametrans.config.AppProperties;
import com.gametrans.deepl.DeepLTranslator;
import com.gametrans.job.BatchExecutor;
import com.gametrans.job.JobContext;
import com.gametrans.job.JobDtos.JobDto;
import com.gametrans.job.JobRunner;
import com.gametrans.job.JobService;
import com.gametrans.job.TranslationJob;
import com.gametrans.llm.LlmClient;
import com.gametrans.llm.LlmClient.TranslateBatchRequest;
import com.gametrans.llm.LlmClient.TranslateRow;
import com.gametrans.llm.LlmClient.TranslationResult;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.ProjectRepository;
import com.gametrans.project.ProjectService;
import com.gametrans.glossary.GlossaryPrompt;
import com.gametrans.review.TermMissService;
import com.gametrans.memory.MemoryService;
import com.gametrans.text.EmptyMarkers;
import com.gametrans.text.GlossaryCheck;
import com.gametrans.text.PlaceholderCheck;
import com.gametrans.translate.TranslateDtos.TranslateRequest;
import com.gametrans.translate.TranslateDtos.TranslateStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class TranslationService {
    private final SegmentRepository segments;
    private final RevisionService revisions;
    private final JobService jobs;
    private final JobRunner runner;
    private final BatchExecutor batches;
    private final LlmClient llm;
    private final ProjectService projects;
    private final AppProperties props;
    private final MemoryService memory;
    private final TermMissService termMiss;
    private final DeepLTranslator translator;
    private final ProjectRepository projectRepo;

    /** 프로젝트별 마지막 실행의 묶음 크기 (상태 카드 표시용). */
    private final Map<Long, Integer> lastBatchSize = new ConcurrentHashMap<>();

    public TranslationService(SegmentRepository segments, RevisionService revisions, JobService jobs,
                              JobRunner runner, LlmClient llm, ProjectService projects, AppProperties props,
                              BatchExecutor batches, MemoryService memory, TermMissService termMiss,
                              DeepLTranslator translator, ProjectRepository projectRepo) {
        this.translator = translator;
        this.projectRepo = projectRepo;
        this.memory = memory;
        this.termMiss = termMiss;
        this.batches = batches;
        this.segments = segments;
        this.revisions = revisions;
        this.jobs = jobs;
        this.runner = runner;
        this.llm = llm;
        this.projects = projects;
        this.props = props;
    }

    public JobDto start(Long projectId, TranslateRequest req) {
        projects.get(projectId);
        if (props.translate().deepl()) {
            if (!translator.configured()) throw new IllegalArgumentException("DeepL 키가 없습니다. DEEPL_AUTH_KEY를 설정하세요.");
            String lang = "EN".equalsIgnoreCase(req.sourceLang()) ? "EN" : "ZH";
            String sheet = req.sheet() == null || req.sheet().isBlank() ? null : req.sheet();
            projectRepo.updateTranslateSourceLang(projectId, lang);
            TranslationJob job = jobs.create(projectId, TranslationJob.Kind.TRANSLATE);
            runner.run(job.getId(), ctx -> translateDeepL(ctx, projectId, sheet, lang));
            return jobs.get(job.getId());
        }
        int batchSize = req.batchSize() == null || req.batchSize() < 1 ? props.translate().batchSize() : req.batchSize();
        String basis = "CN_ONLY".equals(req.basis()) ? "CN_ONLY" : "EN_FIRST";
        TranslationJob job = jobs.create(projectId, TranslationJob.Kind.TRANSLATE);
        lastBatchSize.put(projectId, batchSize);
        projects.bumpStep(projectId, 4);
        runner.run(job.getId(), ctx -> translate(ctx, projectId, basis, req.instructions(), batchSize));
        return jobs.get(job.getId());
    }

    void translate(JobContext ctx, Long projectId, String basis, String instructions, int batchSize) {
        // 용어 누락 표시와 재시도·메모리 재사용 판단은 게임의 유효 단어장으로 한다(검수 화면의 표시와 같은 기준).
        // 프롬프트에는 같은 단어장을 메모째 넣는다.
        Map<String, String> glossary = termMiss.glossaryFor(projectId);
        Map<String, String> prompt = termMiss.promptTermsFor(projectId);
        List<Segment> targets = fillFromMemory(ctx, projectId, segments.findUntranslated(projectId), glossary);
        MemoryService.Lookup mem = memory.lookup(projectId);
        List<List<Segment>> batchList = new ArrayList<>();
        for (int i = 0; i < targets.size(); i += batchSize) batchList.add(targets.subList(i, Math.min(i + batchSize, targets.size())));
        ctx.progress(0, batchList.size());
        ctx.log("묶음 " + batchList.size() + "개를 최대 " + batches.concurrency() + "개씩 동시에 GPT에 보냅니다");
        List<Integer> counts = batches.map(ctx, batchList, (i, batch) -> {
            ctx.log("묶음 " + (i + 1) + "/" + batchList.size() + " (" + batch.size() + "행) 번역 중…");
            Map<String, Segment> byIndex = new LinkedHashMap<>();
            for (Segment s : batch) byIndex.put(s.getIndexLabel(), s);

            Map<String, String> ok = new LinkedHashMap<>();
            // 자리표시자는 맞는데 단어장 용어가 빠진 결과. 재시도해도 안 고쳐지면 이걸 저장하고 용어 누락으로 표시한다.
            Map<String, String> termMissed = new LinkedHashMap<>();
            List<Segment> pending = new ArrayList<>(batch);
            for (int attempt = 0; attempt < 2 && !pending.isEmpty(); attempt++) {
                List<Segment> next = new ArrayList<>();
                Map<String, String> got = call(basis, instructions, prompt, mem, pending);
                for (Segment s : pending) {
                    String ko = got.get(s.getIndexLabel());
                    if (ko == null || ko.isBlank() || !PlaceholderCheck.matches(s.getSrcText(), ko)) { next.add(s); continue; }
                    if (GlossaryCheck.missing(s.getSrcText(), ko, glossary).isEmpty()) { ok.put(s.getIndexLabel(), ko); termMissed.remove(s.getIndexLabel()); }
                    else { termMissed.put(s.getIndexLabel(), ko); next.add(s); }
                }
                pending = next;
            }
            // 작업이 시작할 때 읽어 둔 사본을 통째로 저장하면, 작업이 도는 몇십 분 사이에 사람이 바꾼
            // 담당·완료가 예전 값으로 되돌아간다. 이 작업이 바꾸는 칸만 골라 쓴다.
            for (Map.Entry<String, String> e : ok.entrySet()) {
                Segment s = byIndex.get(e.getKey());
                revisions.add(s.getId(), e.getValue(), TranslationRevision.Author.LLM);
                if (s.getTermMiss() != null) segments.updateTermMiss(s.getId(), null);
            }
            for (Map.Entry<String, String> e : termMissed.entrySet()) {
                Segment s = byIndex.get(e.getKey());
                revisions.add(s.getId(), e.getValue(), TranslationRevision.Author.LLM);
                segments.updateTermMiss(s.getId(), GlossaryCheck.format(GlossaryCheck.missing(s.getSrcText(), e.getValue(), glossary)));
            }
            int unresolved = pending.size() - termMissed.size();
            String miss = unresolved == 0 ? "" : " · " + unresolved + "행 누락 → 다음 실행에서 재시도";
            String termMissNote = termMissed.isEmpty() ? "" : " · 용어 누락 " + termMissed.size() + "행 (검수에서 확인)";
            ctx.log("묶음 " + (i + 1) + " → " + (ok.size() + termMissed.size()) + "/" + batch.size() + "행 채움" + termMissNote + miss);
            return ok.size() + termMissed.size();
        });
        int translated = counts.stream().filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        if (ctx.stopRequested()) { ctx.log("중지됨 (" + translated + "행 번역됨)"); return; }
        proofread(ctx, projectId, instructions, glossary, prompt, batchSize);
        if (ctx.stopRequested()) { ctx.log("중지됨 (" + translated + "행 번역됨)"); return; }
        long remaining = segments.countUntranslated(projectId);
        long proofPending = segments.countProofPending(projectId);
        ctx.log("완료: " + translated + "행 번역, 남은 미번역 " + remaining + "행" + (proofPending == 0 ? "" : ", 교정 판정 대기 " + proofPending + "행"));
        if (remaining == 0 && proofPending == 0) projects.bumpStep(projectId, 5);
    }

    /** DeepL 묶음 크기(행). 번역기가 안에서 원문 50개씩 나눠 보낸다 — 이건 진행 표시와 실패 범위의 단위다. */
    static final int DEEPL_CHUNK = 500;

    /** DeepL로 빈 행을 채운다(설계 2026-10-12 §1). 묶음 하나가 실패하면 그 행은 미번역으로 남겨 다음 실행에서 다시 한다. */
    void translateDeepL(JobContext ctx, Long projectId, String sheet, String sourceLang) {
        Map<String, String> glossary = termMiss.glossaryFor(projectId);
        List<Segment> untranslated = segments.findUntranslated(projectId).stream()
                .filter(s -> sheet == null || sheet.equals(s.getSheetName())).toList();
        List<Segment> targets = fillFromMemory(ctx, projectId, untranslated, glossary);
        int fromMemory = untranslated.size() - targets.size();
        Long gameId = termMiss.gameIdOf(projectId);
        List<List<Segment>> chunks = new ArrayList<>();
        for (int i = 0; i < targets.size(); i += DEEPL_CHUNK) chunks.add(targets.subList(i, Math.min(i + DEEPL_CHUNK, targets.size())));
        ctx.log("DeepL 번역 시작: " + targets.size() + "행" + (sheet == null ? "" : " (시트 " + sheet + ")") + ("EN".equals(sourceLang) ? " · 영어 원문" : ""));
        ctx.progress(0, chunks.size());
        int deepl = 0, cached = 0, flagged = 0, chars = 0, failed = 0;
        for (int c = 0; c < chunks.size(); c++) {
            if (ctx.stopRequested()) { ctx.log("중지됨"); break; }
            List<Segment> chunk = chunks.get(c);
            List<DeepLTranslator.Item> items = chunk.stream()
                    .map(s -> new DeepLTranslator.Item(String.valueOf(s.getId()), s.getSrcText(), EmptyMarkers.isEmpty(s.getEnText()) ? null : s.getEnText()))
                    .toList();
            DeepLTranslator.Result r;
            try {
                r = translator.translate(gameId, items, sourceLang);
            } catch (RuntimeException e) {
                ctx.log("묶음 " + (c + 1) + "/" + chunks.size() + " 실패(" + chunk.size() + "행은 다음 실행에서 다시): " + e.getMessage());
                ctx.progress(c + 1, chunks.size());
                continue;
            }
            chars += r.sentChars();
            failed += r.failedTexts();
            if (r.failedTexts() > 0) ctx.log("묶음 " + (c + 1) + "/" + chunks.size() + ": DeepL이 원문 " + r.failedTexts() + "개를 거절함(다음 실행에서 다시): " + r.lastError());
            for (Segment s : chunk) {
                DeepLTranslator.Outcome o = r.byKey().get(String.valueOf(s.getId()));
                if (o == null || o.ko().isBlank()) continue;
                // DeepL을 기다리는 사이 사람이 저장했으면 덮어쓰지 않는다
                if (revisions.current(s.getId()).isPresent()) continue;
                revisions.add(s.getId(), o.ko(), TranslationRevision.Author.DEEPL);
                segments.updateTermMiss(s.getId(), GlossaryCheck.format(GlossaryCheck.missing(s.getSrcText(), o.ko(), glossary)));
                if (o.source() == DeepLTranslator.Source.CACHE) cached++; else deepl++;
                if (o.problem() != null) { flag(s, DeepLTranslator.problemNote(o.problem())); flagged++; }
            }
            ctx.progress(c + 1, chunks.size());
        }
        ctx.log("DeepL 번역 끝: DeepL " + deepl + "행 · 캐시 " + cached + "행 · 메모리 " + fromMemory + "행 · 확인 필요 " + flagged + "행 · 보낸 글자 " + chars + "자"
                + (failed == 0 ? "" : " · 거절 " + failed + "문장(다음 실행에서 다시)"));
    }

    /** 확인 필요를 켜고 비고에 이유를 붙인다(기존 비고는 남기고 뒤에). */
    private void flag(Segment s, String reason) {
        segments.updateMarks(List.of(s.getId()), null, null, null, true, null, null);
        // 작업 시작 때 읽은 비고가 아니라 지금 비고 — 그 사이 검토자가 쓴 비고를 지우지 않게
        String current = segments.findById(s.getId()).map(Segment::getNote).orElse(null);
        String note = current == null || current.isBlank() ? reason : current + " · " + reason;
        segments.updateNote(s.getId(), note);
    }

    /**
     * 교정 모드: 기존 한국어가 있는 행을 GPT에 보여 주고 유지/수정을 판정받는다. 수정은 새 판으로 쌓여 기존 문장이 이력에 남는다.
     * 응답에서 빠졌거나 고친 문장이 자리표시자를 깨면 한 번 더 묻고, 그래도 안 되면 판정 대기로 남겨 다음 실행에서 다시 묻는다.
     */
    private void proofread(JobContext ctx, Long projectId, String instructions, Map<String, String> glossary, Map<String, String> prompt, int batchSize) {
        List<Segment> targets = segments.findProofPending(projectId);
        if (targets.isEmpty()) return;
        List<List<Segment>> batchList = new ArrayList<>();
        for (int i = 0; i < targets.size(); i += batchSize) batchList.add(targets.subList(i, Math.min(i + batchSize, targets.size())));
        ctx.progress(0, batchList.size());
        ctx.log("기존 번역 교정: " + targets.size() + "행을 묶음 " + batchList.size() + "개로 검토합니다 (틀린 행만 고침)");
        List<Integer> counts = batches.map(ctx, batchList, (i, batch) -> {
            ctx.log("교정 묶음 " + (i + 1) + "/" + batchList.size() + " (" + batch.size() + "행) 검토 중…");
            int fixed = 0;
            List<Segment> pending = new ArrayList<>(batch);
            for (int attempt = 0; attempt < 2 && !pending.isEmpty(); attempt++) {
                Map<String, LlmClient.ProofreadResult> got = callProofread(instructions, prompt, pending);
                List<Segment> next = new ArrayList<>();
                for (Segment s : pending) {
                    LlmClient.ProofreadResult r = got.get(s.getIndexLabel());
                    String existing = revisions.current(s.getId()).map(TranslationRevision::getText).orElse(null);
                    if (r == null || existing == null) { next.add(s); continue; }
                    String ko = r.ko() == null ? null : r.ko().trim();
                    boolean fix = "FIX".equalsIgnoreCase(r.verdict()) && ko != null && !ko.isBlank() && !ko.equals(existing);
                    if (fix && !PlaceholderCheck.matches(s.getSrcText(), ko)) { next.add(s); continue; }
                    String finalText = fix ? ko : existing;
                    String termMiss = GlossaryCheck.format(GlossaryCheck.missing(s.getSrcText(), finalText, glossary));
                    if (fix) {
                        revisions.add(s.getId(), ko, TranslationRevision.Author.LLM_PROOFREAD);
                        segments.markProofFixed(s.getId(), termMiss, r.reason() == null || r.reason().isBlank() ? "수정" : r.reason().trim());
                        fixed++;
                    } else segments.markProofKept(s.getId(), termMiss);
                }
                pending = next;
            }
            // 두 번 물어도 판정을 못 받았거나 고친 문장이 자리표시자를 깨는 행(<br/> 위치를 옮기는 식)은 몇 번을 다시
            // 돌려도 같은 결과라, 판정 대기로 남겨 두면 다음 단계가 영영 안 열린다. 기존 문장을 유지로 확정한다.
            int kept = 0;
            for (Segment s : pending) {
                String existing = revisions.current(s.getId()).map(TranslationRevision::getText).orElse(null);
                if (existing == null) continue;
                segments.markProofKept(s.getId(), GlossaryCheck.format(GlossaryCheck.missing(s.getSrcText(), existing, glossary)));
                kept++;
            }
            String miss = kept == 0 ? "" : " · " + kept + "행은 고친 문장이 태그를 깨서 기존 문장 유지";
            ctx.log("교정 묶음 " + (i + 1) + " → 유지 " + (batch.size() - pending.size() - fixed + kept) + "행 · 수정 " + fixed + "행" + miss);
            return fixed;
        });
        int fixedTotal = counts.stream().filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        ctx.log("교정 완료: " + targets.size() + "행 중 " + fixedTotal + "행 수정 (검수 화면에서 사유와 함께 확인)");
    }

    private Map<String, LlmClient.ProofreadResult> callProofread(String instructions, Map<String, String> glossary, List<Segment> rows) {
        List<LlmClient.ProofreadRow> input = new ArrayList<>();
        for (Segment s : rows) {
            String ko = revisions.current(s.getId()).map(TranslationRevision::getText).orElse(null);
            if (ko != null) input.add(new LlmClient.ProofreadRow(s.getIndexLabel(), s.getSrcText(), s.getEnText(), ko));
        }
        Map<String, LlmClient.ProofreadResult> out = new LinkedHashMap<>();
        if (input.isEmpty()) return out;
        for (LlmClient.ProofreadResult r : llm.proofreadBatch(new LlmClient.ProofreadBatchRequest(instructions, relevantGlossary(glossary, rows), input)))
            if (r.index() != null) out.put(r.index(), r);
        return out;
    }

    /** 같은 게임의 번역 메모리에 원문이 완전히 같은 문장이 있으면 GPT 없이 그대로 채우고, 남은 행만 돌려준다. */
    private List<Segment> fillFromMemory(JobContext ctx, Long projectId, List<Segment> targets, Map<String, String> glossary) {
        MemoryService.Lookup mem = memory.lookup(projectId);
        if (mem.isEmpty()) return targets;
        List<Segment> remaining = new ArrayList<>();
        int filled = 0;
        for (Segment s : targets) {
            String ko = mem.exact(s.getSrcText());
            // 메모리 문장이 지금 단어장과 어긋나면 GPT로 보낸다
            if (ko != null && PlaceholderCheck.matches(s.getSrcText(), ko) && GlossaryCheck.missing(s.getSrcText(), ko, glossary).isEmpty()) {
                revisions.add(s.getId(), ko, TranslationRevision.Author.MEMORY);
                filled++;
            } else remaining.add(s);
        }
        if (filled > 0) ctx.log("번역 메모리에서 " + filled + "행 채움 (원문 동일 · GPT 호출 없음)");
        return remaining;
    }

    private Map<String, String> call(String basis, String instructions, Map<String, String> glossary, MemoryService.Lookup mem, List<Segment> rows) {
        List<TranslateRow> input = rows.stream().map(s -> new TranslateRow(s.getIndexLabel(), s.getSrcText(), s.getEnText())).toList();
        List<LlmClient.Reference> refs = mem.references(rows.stream().map(Segment::getSrcText).toList());
        Map<String, String> out = new LinkedHashMap<>();
        for (TranslationResult r : llm.translateBatch(new TranslateBatchRequest(basis, instructions, relevantGlossary(glossary, rows), input, refs)))
            if (r.index() != null && r.ko() != null) out.put(r.index(), r.ko().trim());
        return out;
    }

    /**
     * 이 묶음의 원문에 실제로 등장하는 용어만 프롬프트에 넣는다. 확정 용어가 수천 개면 전부 넣을 때
     * 묶음마다 프롬프트가 수만 토큰이 되고, 모델도 긴 목록을 무시하기 쉽다.
     */
    static Map<String, String> relevantGlossary(Map<String, String> glossary, List<Segment> rows) {
        if (glossary.isEmpty()) return glossary;
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : glossary.entrySet())
            for (Segment s : rows)
                if (s.getSrcText() != null && s.getSrcText().contains(e.getKey())) { out.put(e.getKey(), e.getValue()); break; }
        return out;
    }

    public TranslateStatus status(Long projectId) {
        long target = segments.countByProjectIdAndSkippedFalse(projectId);
        long proofPending = segments.countProofPending(projectId);
        long remaining = segments.countUntranslated(projectId) + proofPending;
        JobDto latest = jobs.latest(projectId, TranslationJob.Kind.TRANSLATE).orElse(null);
        int batchSize = lastBatchSize.getOrDefault(projectId, props.translate().batchSize());
        return new TranslateStatus(target, target - remaining, proofPending, termMiss.glossaryFor(projectId).size(), batchSize, latest);
    }
}
