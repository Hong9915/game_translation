package com.gametrans.translate;

import com.gametrans.activity.Activity;
import com.gametrans.activity.ActivityRepository;
import com.gametrans.activity.ActivityService;
import com.gametrans.game.GptInstructions;
import com.gametrans.glossary.EffectiveGlossary;
import com.gametrans.glossary.GlossaryEntry;
import com.gametrans.glossary.GlossaryPrompt;
import com.gametrans.llm.LlmClient;
import com.gametrans.llm.LlmClient.ProofreadResult;
import com.gametrans.llm.LlmClient.ProofreadRow;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.review.ReviewEvents;
import com.gametrans.review.ReviewService;
import com.gametrans.reviewer.Reviewer;
import com.gametrans.reviewer.ReviewerService;
import com.gametrans.text.EmptyMarkers;
import com.gametrans.text.PlaceholderCheck;
import com.gametrans.translate.TranslateDtos.PolishResult;
import com.gametrans.translate.TranslateDtos.Proposal;
import com.gametrans.translate.TranslateDtos.SaveBatchRequest;
import com.gametrans.translate.TranslateDtos.SaveBatchResult;
import com.gametrans.translate.TranslateDtos.SaveItem;
import com.gametrans.translate.TranslateDtos.Skip;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검수 그리드의 GPT로 다듬기(설계 2026-11-13). polish는 고친 안만 돌려주고 저장하지 않는다 — 사용자가 전/후를 보고 고른 행만
 * saveBatch로 넣는다. 넣기는 사람이 저장한 것과 같다(HUMAN 판·메모리·같은 원문 행 반영). 확정·제외 행은 둘 다 건너뛴다.
 */
@Service
public class PolishService {
    static final int MAX_POLISH = 50;
    static final int MAX_SAVE = 500;

    private final SegmentBatch batches;
    private final LlmClient llm;
    private final EffectiveGlossary effective;
    private final RevisionService revisions;
    private final ReviewService review;
    private final ActivityService activities;
    private final ReviewerService reviewers;
    private final ReviewEvents events;
    private final ActivityRepository activityRepo;
    private final TranslationRevisionRepository revisionRepo;
    private final SegmentRepository segmentRepo;

    public PolishService(SegmentBatch batches, LlmClient llm, EffectiveGlossary effective, RevisionService revisions, ReviewService review,
                         ActivityService activities, ReviewerService reviewers, ReviewEvents events,
                         ActivityRepository activityRepo, TranslationRevisionRepository revisionRepo, SegmentRepository segmentRepo) {
        this.batches = batches;
        this.llm = llm;
        this.effective = effective;
        this.revisions = revisions;
        this.review = review;
        this.activities = activities;
        this.reviewers = reviewers;
        this.events = events;
        this.activityRepo = activityRepo;
        this.revisionRepo = revisionRepo;
        this.segmentRepo = segmentRepo;
    }

    /** GPT 한 번으로 고른 행의 Fix를 다듬은 안을 받는다. 트랜잭션 밖 — GPT를 기다리는 동안 DB 연결을 붙잡지 않는다. */
    public PolishResult polish(List<Long> segmentIds) {
        SegmentBatch.Loaded loaded = batches.load(segmentIds, MAX_POLISH, "다듬을");
        List<Skip> skipped = new ArrayList<>();
        List<Segment> todo = new ArrayList<>();
        for (Long id : loaded.ids()) {
            Segment s = loaded.byId().get(id);
            String why = SegmentBatch.lockedReason(s);
            if (why == null && (s.getLatestKo() == null || s.getLatestKo().isBlank())) why = "Fix 없음";
            if (why != null) skipped.add(new Skip(id, why));
            else todo.add(s);
        }
        if (todo.isEmpty()) return new PolishResult(List.of(), 0, skipped);

        // 행들에 걸리는 단어장(메모째)과 단어장 메모 전용 항목의 지시를 합친다 — 여러 행 GPT 번역과 같은 방식
        List<GlossaryEntry> entries = effective.of(loaded.game().getId());
        Map<String, String> glossary = new LinkedHashMap<>();
        Set<String> notes = new LinkedHashSet<>();
        List<ProofreadRow> rows = new ArrayList<>();
        for (Segment s : todo) {
            QuickTranslateService.Terms t = QuickTranslateService.termsFor(entries, s.getSrcText());
            t.withNotes().forEach(glossary::putIfAbsent);
            notes.addAll(t.notes());
            rows.add(new ProofreadRow(String.valueOf(s.getId()), s.getSrcText(),
                    EmptyMarkers.isEmpty(s.getEnText()) ? null : s.getEnText().trim(), s.getLatestKo()));
        }
        String instructions = GlossaryPrompt.instructions(
                GptInstructions.full(GptInstructions.POLISH_BASE, loaded.game().getGptPolishExtra()), List.copyOf(notes));

        Map<String, ProofreadResult> got = new HashMap<>();
        for (ProofreadResult r : llm.polishBatch(new LlmClient.ProofreadBatchRequest(instructions, glossary, rows)))
            if (r.index() != null) got.putIfAbsent(r.index(), r);

        List<Proposal> items = new ArrayList<>();
        int kept = 0;
        for (Segment s : todo) {
            ProofreadResult r = got.get(String.valueOf(s.getId()));
            if (r == null) { skipped.add(new Skip(s.getId(), "다듬기 결과 없음")); continue; }
            String before = s.getLatestKo();
            String after = r.ko() == null ? "" : r.ko().trim();
            // 공백만 바뀐 안은 고친 것으로 보지 않는다
            if (!"FIX".equalsIgnoreCase(r.verdict()) || after.isEmpty() || after.equals(before.trim())) { kept++; continue; }
            if (!PlaceholderCheck.matches(s.getSrcText(), after)) { skipped.add(new Skip(s.getId(), "자리표시자 어긋남")); continue; }
            items.add(new Proposal(s.getId(), before, after, r.reason()));
        }
        return new PolishResult(items, kept, skipped);
    }

    /**
     * 고른 제안을 사람이 저장한 것처럼 한 트랜잭션으로 넣는다. 변경 내역은 파일마다 POLISH 하나 — 같은 원문이라 따라 바뀐 행의 판도
     * 같은 항목에 달려 Ctrl+Z 한 번에 함께 되돌아간다. 확정·제외 행, 빈 글, 제안을 받은 뒤 Fix가 바뀐 행(그 사이 바뀜)은 건너뛴다.
     * 지금 Fix와 같은 글(앞 행을 넣으며 이미 같은 글로 따라 바뀐 행 포함)은 넣은 수·건너뛴 수가 아니라 unchanged로 센다.
     * 출처가 CONNECTOR면 검수자 헤더가 꼭 있어야 한다 — 없거나 지워진 검수자면 400이고 아무것도 쓰지 않는다.
     */
    @Transactional
    public SaveBatchResult saveBatch(SaveBatchRequest req, Long reviewerId) {
        List<SaveItem> items = req == null || req.items() == null ? List.of() : req.items().stream().filter(Objects::nonNull).toList();
        if (items.isEmpty() || items.size() > MAX_SAVE) throw new IllegalArgumentException("한 번에 1~" + MAX_SAVE + "행을 넣을 수 있습니다.");
        if (items.stream().anyMatch(it -> it.segmentId() == null)) throw new IllegalArgumentException("행 id가 빠진 항목이 있습니다.");
        String source = req.source();
        if (source != null && !source.isBlank() && !"CONNECTOR".equals(source)) throw new IllegalArgumentException("알 수 없는 출처입니다: " + source);
        boolean connector = "CONNECTOR".equals(source);
        SegmentBatch.Loaded loaded = batches.load(items.stream().map(SaveItem::segmentId).toList(), MAX_SAVE, "넣을");
        Reviewer who = connector ? reviewers.requireForConnector(reviewerId) : reviewers.resolve(reviewerId);
        Long by = who == null ? null : who.getId();

        Function<Long, Long> lazy = activities.lazyPerProject(connector ? Activity.Kind.CONNECTOR : Activity.Kind.POLISH, by, Map.of());
        Map<Long, Long> made = new LinkedHashMap<>();
        Function<Long, Long> activityFor = pid -> made.computeIfAbsent(pid, lazy);

        int saved = 0, skipped = 0, unchanged = 0, propagated = 0;
        List<Skip> skippedRows = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (SaveItem it : items) {
            if (!seen.add(it.segmentId())) continue;   // 같은 행이 두 번 오면 앞의 것만
            Segment s = loaded.byId().get(it.segmentId());
            String text = it.text() == null ? "" : it.text().trim();
            String locked = SegmentBatch.lockedReason(s);
            if (locked != null) { skipped++; skippedRows.add(new Skip(s.getId(), locked)); continue; }
            if (text.isEmpty()) { skipped++; skippedRows.add(new Skip(s.getId(), "빈 글")); continue; }
            // 판 테이블에서 읽는다 — 앞 행의 같은 원문 반영은 벌크 update라 들고 있는 엔티티의 latest_ko는 낡았다
            String current = revisions.current(s.getId()).map(TranslationRevision::getText).orElse(null);
            if (text.equals(current)) { unchanged++; continue; }
            if (!Objects.equals(current, it.base())) { skipped++; skippedRows.add(new Skip(s.getId(), "그 사이 바뀜")); continue; }
            propagated += review.saveHuman(s, text, by, activityFor);
            saved++;
        }
        int finalMarked = connector ? markFinalLane(made.values()) : 0;
        for (Long pid : made.keySet()) events.refresh(pid, by);
        return new SaveBatchResult(saved, skipped, unchanged, propagated, List.copyOf(made.values()), skippedRows, finalMarked);
    }

    /**
     * 커넥터가 넣은 행(따라 바뀐 행 포함) 중 최종 리뷰 레인이고 아직 표시가 꺼진 것만 최종 수정으로 켠다.
     * 항목(파일)마다 이번에 새로 켠 행 id를 detail.finalMarked에 남긴다 — 되돌릴 때 이 행들의 표시를 다시 끈다.
     */
    private int markFinalLane(java.util.Collection<Long> activityIds) {
        int total = 0;
        for (Long aid : activityIds) {
            List<Long> touched = revisionRepo.findByActivityIdOrderByIdAsc(aid).stream()
                    .map(TranslationRevision::getSegmentId).distinct().toList();
            List<Long> fresh = touched.isEmpty() ? List.of() : segmentRepo.finalLaneUnmarked(touched);
            if (!fresh.isEmpty()) segmentRepo.setFinalMark(fresh, true);
            Activity a = activityRepo.findById(aid).orElseThrow();
            Map<String, Object> detail = new HashMap<>(a.getDetail() == null ? Map.of() : a.getDetail());
            detail.put("finalMarked", new ArrayList<>(fresh));
            a.setDetail(detail);
            activityRepo.save(a);
            total += fresh.size();
        }
        return total;
    }
}
