package com.gametrans.review;

import com.gametrans.activity.Activity;
import com.gametrans.activity.ActivityService;
import com.gametrans.common.NotFoundException;
import com.gametrans.config.AppProperties;
import com.gametrans.job.BatchExecutor;
import com.gametrans.job.JobContext;
import com.gametrans.job.JobDtos.JobDto;
import com.gametrans.job.JobRunner;
import com.gametrans.job.JobService;
import com.gametrans.job.TranslationJob;
import com.gametrans.llm.LlmClient;
import com.gametrans.llm.LlmClient.FixTermsRow;
import com.gametrans.llm.LlmClient.ShortenRow;
import com.gametrans.llm.LlmClient.TranslationResult;
import com.gametrans.memory.MemoryEntry;
import com.gametrans.memory.MemoryService;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.review.ReviewDtos.ReviewStats;
import com.gametrans.review.ReviewDtos.RevisionDto;
import com.gametrans.review.ReviewDtos.SegmentFilter;
import com.gametrans.review.ReviewDtos.SegmentRow;
import com.gametrans.review.ReviewDtos.SegmentsResponse;
import com.gametrans.review.ReviewDtos.SheetStat;
import com.gametrans.reviewer.Reviewer;
import com.gametrans.reviewer.ReviewerService;
import com.gametrans.text.GlossaryCheck;
import com.gametrans.text.LengthCheck;
import com.gametrans.text.PlaceholderCheck;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService {
    static final int SHORTEN_CHUNK = 30;

    private final SegmentRepository segments;
    private final RevisionService revisions;
    private final JobService jobs;
    private final JobRunner runner;
    private final LlmClient llm;
    private final BatchExecutor batches;
    private final AppProperties props;
    private final MemoryService memory;
    private final ReviewerService reviewers;
    private final ReviewEvents events;
    private final DuplicateSyncService duplicates;
    private final TermMissService termMiss;
    private final ActivityService activities;

    public ReviewService(SegmentRepository segments, RevisionService revisions, JobService jobs, JobRunner runner, LlmClient llm, AppProperties props,
                         BatchExecutor batches, MemoryService memory, ReviewerService reviewers, ReviewEvents events,
                         DuplicateSyncService duplicates, TermMissService termMiss, ActivityService activities) {
        this.activities = activities;
        this.memory = memory;
        this.batches = batches;
        this.segments = segments;
        this.revisions = revisions;
        this.jobs = jobs;
        this.runner = runner;
        this.llm = llm;
        this.props = props;
        this.reviewers = reviewers;
        this.events = events;
        this.duplicates = duplicates;
        this.termMiss = termMiss;
    }

    public double defaultRatio() { return props.translate().lengthRatioDefault(); }

    /** 건너뛰지 않은 전체 세그먼트를 인덱스 순으로. */
    public List<SegmentRow> rows(Long projectId, double ratio) {
        List<Segment> segs = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(projectId);
        Map<Long, List<TranslationRevision>> revs = revisions.bySegmentIds(segs.stream().map(Segment::getId).toList());
        Map<Long, Reviewer> who = reviewers.byId();
        List<SegmentRow> out = new ArrayList<>();
        for (Segment s : segs) out.add(row(s, revs.getOrDefault(s.getId(), List.of()), ratio, who));
        return out;
    }

    /** 세그먼트 하나의 현재 행. */
    public SegmentRow row(Long segmentId, double ratio) {
        Segment s = segments.findById(segmentId).orElseThrow(() -> new NotFoundException("세그먼트를 찾을 수 없습니다: " + segmentId));
        return row(s, revisions.bySegmentIds(List.of(segmentId)).getOrDefault(segmentId, List.of()), ratio, reviewers.byId());
    }

    /** 통계는 프로젝트 전체 기준, 필터는 목록에만 적용한다. */
    public SegmentsResponse segments(Long projectId, SegmentFilter f, Double ratioParam, int page, int size) {
        double ratio = ratioParam == null ? defaultRatio() : ratioParam;
        List<SegmentRow> all = rows(projectId, ratio);
        long filled = all.stream().filter(r -> r.currentText() != null).count();
        long flagged = all.stream().filter(SegmentRow::flagged).count();
        long termMiss = all.stream().filter(r -> !r.termMiss().isEmpty()).count();
        long revised = all.stream().filter(r -> r.history().size() > 1).count();
        long proofFixed = all.stream().filter(r -> r.proofNote() != null).count();
        long proofPending = all.stream().filter(SegmentRow::proofPending).count();
        List<SegmentRow> filtered = all.stream()
                .filter(r -> f.flaggedOnly() == null || !f.flaggedOnly() || r.flagged() || !r.termMiss().isEmpty())
                .filter(r -> !f.proofOnly() || r.proofNote() != null)
                .filter(r -> !f.proofPendingOnly() || r.proofPending())
                .filter(r -> f.sheet() == null || f.sheet().equals(r.sheetName()))
                .filter(r -> f.assignee() == null || ("none".equals(f.assignee()) ? r.assignee() == null
                        : r.assignee() != null && String.valueOf(r.assignee().id()).equals(f.assignee())))
                .filter(r -> f.done() == null || f.done() == r.done())
                .toList();
        return new SegmentsResponse(new ReviewStats(filled, all.size(), flagged, termMiss, revised, proofFixed, ratio, proofPending),
                com.gametrans.config.Paging.slice(filtered, page, size), page, size, filtered.size(), sheetStats(all));
    }

    /** 시트 탭용 집계 — sheet 필터와 무관하게 항상 전체, all이 시트 이름순이라 결과도 그 순서다. */
    private static List<SheetStat> sheetStats(List<SegmentRow> all) {
        Map<String, long[]> acc = new LinkedHashMap<>();   // [total, flagged, termMiss, done]
        for (SegmentRow r : all) {
            long[] c = acc.computeIfAbsent(r.sheetName(), k -> new long[4]);
            c[0]++;
            if (r.flagged()) c[1]++;
            if (!r.termMiss().isEmpty()) c[2]++;
            if (r.done()) c[3]++;
        }
        List<SheetStat> out = new ArrayList<>();
        acc.forEach((sheet, c) -> out.add(new SheetStat(sheet, c[0], c[1], c[2], c[3])));
        return out;
    }

    /**
     * 원본 행 저장과 같은 원문 행 전파를 한 트랜잭션으로 묶는다 — 전파가 도중에 실패하면 이 저장 전체가
     * 롤백돼야, 사용자가 500을 받았는데 원본만 이미 커밋돼 데이터가 갈라지는 일이 없다.
     */
    @Transactional
    public ReviewDtos.SaveResult saveHuman(Long segmentId, String text, Double ratioParam, Long reviewerId) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("번역 문장이 비어 있습니다.");
        Reviewer who = reviewers.resolve(reviewerId);
        Segment s = segments.findById(segmentId).orElseThrow(() -> new NotFoundException("세그먼트를 찾을 수 없습니다: " + segmentId));
        String trimmed = text.trim();
        double ratio = ratioParam == null ? defaultRatio() : ratioParam;
        Long by = who == null ? null : who.getId();
        // 변경 내역(설계 2026-09-30): 리비전이 실제로 하나라도 써질 때만 만든다. 대상 행이 그대로여도 같은 원문 행이
        // 따라 바뀌면 그 리비전들이 매달릴 활동은 있어야 하므로 지연 생성 함수로 넘긴다.
        Long[] made = { null };
        Function<Long, Long> activityFor = pid -> {
            if (made[0] == null) made[0] = activities.start(s.getProjectId(), Activity.Kind.EDIT, by, Map.of("segmentId", segmentId));
            return made[0];
        };
        int propagated = saveHuman(s, trimmed, by, activityFor);
        SegmentRow saved = row(segmentId, ratio);
        events.segmentUpdated(s.getProjectId(), by, saved);
        return new ReviewDtos.SaveResult(saved, propagated, made[0]);
    }

    /**
     * 판 쓰기·메모리·용어 누락 재검사·같은 원문 전파까지(이벤트·응답 행은 부르는 쪽 몫). 전파된 행 수를 돌려준다.
     * 판이 하나라도 써질 때만 activityFor가 변경 내역을 만든다. 여러 행을 한 번에 저장하는 쪽(GPT 다듬기 PolishService)도 쓴다 —
     * 부르는 쪽이 트랜잭션을 열고 text를 trim해 넘긴다.
     */
    public int saveHuman(Segment s, String trimmed, Long by, Function<Long, Long> activityFor) {
        Long segmentId = s.getId();
        Optional<TranslationRevision> current = revisions.current(segmentId);
        if (current.isEmpty() || !current.get().getText().equals(trimmed)) {
            revisions.add(segmentId, trimmed, TranslationRevision.Author.HUMAN, by, activityFor.apply(s.getProjectId()));
        }
        memory.rememberText(s.getProjectId(), s.getSrcText(), trimmed, MemoryEntry.Source.HUMAN);
        // 사람이 고친 문장으로 용어 누락을 게임의 유효 단어장 기준으로 다시 검사한다. 이 요청이 바꾸는 칸은 용어 누락뿐이라 그 칸만 쓴다
        // (읽은 뒤 저장하기까지 사이에 다른 사람이 누른 완료·담당을 되돌리지 않도록).
        String termMiss = GlossaryCheck.format(GlossaryCheck.missing(s.getSrcText(), trimmed, this.termMiss.glossaryFor(s.getProjectId())));
        segments.updateTermMiss(segmentId, termMiss);
        // updateTermMiss는 벌크 update라 영속성 컨텍스트를 안 지난다 — saveHuman이 한 트랜잭션으로 묶인 지금,
        // 들고 있는 s는 1차 캐시에 그대로 남아 아래 row()가 이 s를 다시 돌려준다. 방금 쓴 값을 직접 반영해
        // row()가 벌크 update 이전의 term_miss를 읽어 오지 않게 한다.
        s.setTermMiss(termMiss);
        return duplicates.syncFrom(s, trimmed, by, activityFor);
    }

    /** sheet가 null이 아니면 그 시트의 플래그 행만 다듬는다 — 시트 탭을 보고 누른 버튼이 남의 시트까지 GPT로 보내지 않도록. */
    public JobDto startShorten(Long projectId, Double ratioParam, String sheet) { return startShorten(projectId, ratioParam, sheet, null); }

    /** 변경 내역은 시작 시점에 하나 만든다 — 진행 중에도 보이고, 리비전이 붙는 만큼 행 수가 는다(0행이면 "바뀐 행 없음"). */
    public JobDto startShorten(Long projectId, Double ratioParam, String sheet, Long reviewerId) {
        double ratio = ratioParam == null ? defaultRatio() : ratioParam;
        // 작업이 거절되면(이미 도는 작업) 변경 내역도 남기지 않도록 jobs.create를 먼저 부른다.
        TranslationJob job = jobs.create(projectId, TranslationJob.Kind.SHORTEN);
        Long activityId = activities.start(projectId, Activity.Kind.SHORTEN, reviewerId, sheetDetail(sheet));
        runner.run(job.getId(), ctx -> shorten(ctx, projectId, ratio, sheet, activityId));
        return jobs.get(job.getId());
    }

    /** detail.sheet — 시트가 없으면(전체) 키를 넣지 않는다. Map.of는 null 값을 못 받는다. */
    private static Map<String, Object> sheetDetail(String sheet) { return sheet == null ? Map.of() : Map.of("sheet", sheet); }

    void shorten(JobContext ctx, Long projectId, double ratio, String sheet, Long activityId) {
        List<SegmentRow> flagged = rows(projectId, ratio).stream()
                .filter(r -> sheet == null || sheet.equals(r.sheetName()))
                .filter(SegmentRow::flagged).toList();
        List<List<SegmentRow>> chunks = new ArrayList<>();
        for (int i = 0; i < flagged.size(); i += SHORTEN_CHUNK) chunks.add(flagged.subList(i, Math.min(i + SHORTEN_CHUNK, flagged.size())));
        ctx.progress(0, chunks.size());
        List<Integer> counts = batches.map(ctx, chunks, (i, chunk) -> {
            ctx.log("묶음 " + (i + 1) + "/" + chunks.size() + " (" + chunk.size() + "행) 짧게 다듬는 중…");
            Map<String, SegmentRow> byIndex = new LinkedHashMap<>();
            List<ShortenRow> input = new ArrayList<>();
            for (SegmentRow r : chunk) {
                byIndex.put(r.indexLabel(), r);
                input.add(new ShortenRow(r.indexLabel(), r.srcText(), r.currentText(), Math.max(1, (int) Math.floor(r.lenSrc() * ratio))));
            }
            int ok = 0;
            for (TranslationResult res : llm.shortenBatch(input)) {
                SegmentRow r = byIndex.get(res.index());
                if (r == null || res.ko() == null || res.ko().isBlank()) continue;
                String ko = res.ko().trim();
                if (ko.equals(r.currentText()) || !PlaceholderCheck.matches(r.srcText(), ko)) continue;
                String latestText = revisions.current(r.id()).map(TranslationRevision::getText).orElse(null);
                if (!Objects.equals(latestText, r.currentText())) {
                    ctx.log(r.indexLabel() + ": 건너뜀: 그 사이 수정됨");
                    continue;
                }
                revisions.add(r.id(), ko, TranslationRevision.Author.LLM_SHORTEN, null, activityId);
                events.segmentUpdated(projectId, null, row(r.id(), ratio));
                ok++;
            }
            ctx.log("묶음 " + (i + 1) + " → " + ok + "/" + chunk.size() + "행 수정본 저장");
            return ok;
        });
        int fixed = counts.stream().filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        if (ctx.stopRequested()) { ctx.log("중지됨 (" + fixed + "행 수정)"); return; }
        ctx.log("완료: " + fixed + "행에 GPT 수정본 추가");
    }

    /** 용어 고치기. segmentIds가 있으면 그 행 가운데서만, 없으면 sheet(null이면 전체)의 용어 누락 행을 GPT로 고친다. */
    public JobDto startFixTerms(Long projectId, Double ratioParam, String sheet, List<Long> segmentIds) {
        return startFixTerms(projectId, ratioParam, sheet, segmentIds, null);
    }

    public JobDto startFixTerms(Long projectId, Double ratioParam, String sheet, List<Long> segmentIds, Long reviewerId) {
        double ratio = ratioParam == null ? defaultRatio() : ratioParam;
        Set<Long> ids = segmentIds == null || segmentIds.isEmpty() ? null : new HashSet<>(segmentIds);
        TranslationJob job = jobs.create(projectId, TranslationJob.Kind.FIX_TERMS);
        Long activityId = activities.start(projectId, Activity.Kind.FIX_TERMS, reviewerId, sheetDetail(sheet));
        runner.run(job.getId(), ctx -> fixTerms(ctx, projectId, ratio, sheet, ids, activityId));
        return jobs.get(job.getId());
    }

    void fixTerms(JobContext ctx, Long projectId, double ratio, String sheet, Set<Long> ids, Long activityId) {
        // 표시를 매길 때와 같은 기준(게임의 유효 단어장)으로 봐야 한다 — 구 용어 표로 보면 단어장 기준 표시를 "빠진 게 없다"며 풀어 버린다.
        Map<String, String> glossary = termMiss.glossaryFor(projectId);
        // GPT에 보낼 때는 단어장 항목의 메모도 같이 싣는다("화염 (의역: 불꽃 속성)") — 예전 확정 번역에 메모가 붙어 가던 것과 같은 모양이다.
        Map<String, String> withNotes = termMiss.promptTermsFor(projectId);
        // 표시가 지금 단어장 기준으로도 맞는지 먼저 다시 본다 — 이미 들어갔거나 용어집이 바뀐 행은 GPT에 보내지 않고 표시만 푼다.
        Map<Long, Map<String, String>> need = new LinkedHashMap<>();
        List<SegmentRow> targets = new ArrayList<>();
        int cleared = 0;
        for (SegmentRow r : rows(projectId, ratio)) {
            if (r.termMiss().isEmpty() || r.currentText() == null) continue;
            if (sheet != null && !sheet.equals(r.sheetName())) continue;
            if (ids != null && !ids.contains(r.id())) continue;
            Map<String, String> missing = missingTerms(r.srcText(), r.currentText(), glossary);
            if (missing.isEmpty()) {
                segments.updateTermMiss(r.id(), null);
                events.segmentUpdated(projectId, null, row(r.id(), ratio));
                cleared++;
                continue;
            }
            missing.replaceAll((term, ko) -> withNotes.getOrDefault(term, ko));
            need.put(r.id(), missing);
            targets.add(r);
        }
        if (cleared > 0) ctx.log("다시 검사: " + cleared + "행은 이미 용어가 들어 있어 표시만 풀었습니다");
        List<List<SegmentRow>> chunks = new ArrayList<>();
        for (int i = 0; i < targets.size(); i += SHORTEN_CHUNK) chunks.add(targets.subList(i, Math.min(i + SHORTEN_CHUNK, targets.size())));
        ctx.progress(0, chunks.size());
        if (!chunks.isEmpty()) ctx.log("용어 누락 " + targets.size() + "행을 묶음 " + chunks.size() + "개로 GPT에 보냅니다");
        List<Integer> counts = batches.map(ctx, chunks, (i, chunk) -> {
            ctx.log("묶음 " + (i + 1) + "/" + chunks.size() + " (" + chunk.size() + "행) 용어 넣는 중…");
            Map<String, SegmentRow> byIndex = new LinkedHashMap<>();
            List<FixTermsRow> input = new ArrayList<>();
            for (SegmentRow r : chunk) {
                byIndex.put(r.indexLabel(), r);
                input.add(new FixTermsRow(r.indexLabel(), r.srcText(), r.enText(), r.currentText(), need.get(r.id())));
            }
            int fixed = 0, partial = 0, rejected = 0;
            for (TranslationResult res : llm.fixTermsBatch(input)) {
                SegmentRow r = byIndex.remove(res.index());
                if (r == null || res.ko() == null || res.ko().isBlank()) continue;
                String ko = res.ko().trim();
                Map<String, String> before = need.get(r.id());
                Map<String, String> after = missingTerms(r.srcText(), ko, glossary);
                // 자리표시자를 깨거나, 빠진 용어가 줄지 않았거나, 멀쩡하던 용어를 새로 빼 버린 답은 버린다.
                if (!PlaceholderCheck.matches(r.srcText(), ko) || after.size() >= before.size() || !before.keySet().containsAll(after.keySet())) { rejected++; continue; }
                List<String> still = GlossaryCheck.missing(r.srcText(), ko, glossary);
                String latestText = revisions.current(r.id()).map(TranslationRevision::getText).orElse(null);
                if (!Objects.equals(latestText, r.currentText())) {
                    ctx.log(r.indexLabel() + ": 건너뜀: 그 사이 수정됨");
                    continue;
                }
                revisions.add(r.id(), ko, TranslationRevision.Author.LLM_PROOFREAD, null, activityId);
                segments.markTermsFixed(r.id(), GlossaryCheck.format(still), "GLOSSARY: 용어 반영 " + String.join(", ", before.keySet()));
                events.segmentUpdated(projectId, null, row(r.id(), ratio));
                if (still.isEmpty()) fixed++; else partial++;
            }
            int unanswered = byIndex.size();
            ctx.log("묶음 " + (i + 1) + " → 고침 " + fixed + "행" + (partial > 0 ? " · 일부만 " + partial + "행" : "")
                    + (rejected + unanswered > 0 ? " · 못 고침 " + (rejected + unanswered) + "행" : ""));
            return fixed + partial;
        });
        int total = counts.stream().filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        if (ctx.stopRequested()) { ctx.log("중지됨 (" + total + "행 수정)"); return; }
        ctx.log("완료: " + total + "행에 용어를 넣은 수정본 추가" + (targets.size() > total ? ", " + (targets.size() - total) + "행은 그대로 (검수에서 확인)" : ""));
    }

    /** 빠진 용어 (원문 → 용어집의 확정 번역, 메모째). GPT에 메모도 참고로 보여 주려고 원래 값을 돌려준다. */
    static Map<String, String> missingTerms(String src, String ko, Map<String, String> glossary) {
        Map<String, String> out = new LinkedHashMap<>();
        GlossaryCheck.applicable(src, glossary).forEach((term, target) -> { if (!ko.contains(target)) out.put(term, glossary.get(term)); });
        return out;
    }

    private static SegmentRow row(Segment s, List<TranslationRevision> revs, double ratio, Map<Long, Reviewer> who) {
        TranslationRevision last = revs.isEmpty() ? null : revs.get(revs.size() - 1);
        String current = last == null ? null : last.getText();
        int lenSrc = LengthCheck.count(s.getSrcText());
        int lenKo = LengthCheck.count(current);
        boolean flagged = current != null && LengthCheck.flagged(s.getSrcText(), current, ratio);
        Reviewer editor = last == null || last.getReviewerId() == null ? null : who.get(last.getReviewerId());
        return new SegmentRow(s.getId(), s.getIndexLabel(), s.getPairIndex(), s.getSrcText(), s.getEnText(), current,
                s.getExistingKoText(), lenKo, lenSrc, flagged, GlossaryCheck.parse(s.getTermMiss()), s.getProofNote(), revs.stream().map(r -> RevisionDto.of(r, who)).toList(),
                s.getSheetName(), s.getRowNo(), ReviewerService.ref(s.getAssigneeId() == null ? null : who.get(s.getAssigneeId())), s.isDone(),
                ReviewerService.ref(editor), editor == null ? null : last.getCreatedAt(), s.isProofPending());
    }
}
