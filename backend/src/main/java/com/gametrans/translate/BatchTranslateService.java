package com.gametrans.translate;

import com.gametrans.activity.Activity;
import com.gametrans.activity.ActivityRepository;
import com.gametrans.activity.ActivityService;
import com.gametrans.deepl.DeepLTranslator;
import com.gametrans.game.Game;
import com.gametrans.game.GptInstructions;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.Project;
import com.gametrans.review.ReviewEvents;
import com.gametrans.reviewer.Reviewer;
import com.gametrans.reviewer.ReviewerService;
import com.gametrans.text.EmptyMarkers;
import com.gametrans.translate.QuickTranslateService.Outcome;
import com.gametrans.translate.TranslateDtos.BatchTranslateRequest;
import com.gametrans.translate.TranslateDtos.BatchTranslateResult;
import com.gametrans.translate.TranslateDtos.Engine;
import com.gametrans.translate.TranslateDtos.Skip;
import com.gametrans.workspace.WorkspaceDtos.RowDto;
import com.gametrans.workspace.WorkspaceService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 검수 그리드에서 고른 여러 행을 DeepL 또는 GPT로 다시 번역한다(설계 2026-11-13). 엔진은 요청이 고르고 서버 설정과 무관하다.
 * 한 번에 50행까지, 한 게임 안에서만. 프론트가 큰 선택을 50행씩 나눠 보내고, 앞 묶음이 만든 변경 내역(RETRANSLATE)을 activityIds로 이어 받는다.
 * 확정·제외 행은 건너뛴다. 행 번역과 같이 메모리에 넣지 않고 같은 원문 행에 퍼뜨리지 않는다.
 */
@Service
public class BatchTranslateService {
    static final int MAX_ROWS = 50;

    private final SegmentRepository segments;
    private final SegmentBatch batches;
    private final QuickTranslateService quick;
    private final RowTranslateService rows;
    private final ActivityService activities;
    private final ActivityRepository activityRepo;
    private final ReviewerService reviewers;
    private final WorkspaceService workspace;
    private final ReviewEvents events;
    private final TransactionTemplate tx;

    public BatchTranslateService(SegmentRepository segments, SegmentBatch batches, QuickTranslateService quick,
                                 RowTranslateService rows, ActivityService activities, ActivityRepository activityRepo, ReviewerService reviewers,
                                 WorkspaceService workspace, ReviewEvents events, TransactionTemplate tx) {
        this.segments = segments;
        this.batches = batches;
        this.quick = quick;
        this.rows = rows;
        this.activities = activities;
        this.activityRepo = activityRepo;
        this.reviewers = reviewers;
        this.workspace = workspace;
        this.events = events;
        this.tx = tx;
    }

    public BatchTranslateResult translate(BatchTranslateRequest req, Long reviewerId) {
        if (req == null || req.engine() == null) throw new IllegalArgumentException("번역 엔진(DEEPL 또는 GPT)을 고르세요.");
        SegmentBatch.Loaded loaded = batches.load(req.segmentIds(), MAX_ROWS, "번역할");
        Reviewer who = reviewers.resolve(reviewerId);
        Long by = who == null ? null : who.getId();
        List<Long> ids = loaded.ids();
        Map<Long, Segment> byId = loaded.byId();
        Map<Long, Project> projectOf = loaded.projectOf();
        Game game = loaded.game();
        String instructions = req.engine() == Engine.GPT ? GptInstructions.full(GptInstructions.TRANSLATE_BASE, game.getGptTranslateExtra()) : null;

        // 확정·제외는 보내지 않는다. 나머지는 프로젝트별로(원문 언어가 파일마다 다르다), 요청 순서대로.
        List<Skip> skipped = new ArrayList<>();
        Map<Long, List<Segment>> todo = new LinkedHashMap<>();
        for (Long id : ids) {
            Segment s = byId.get(id);
            String why = reason(s);
            if (why != null) skipped.add(new Skip(id, why));
            else todo.computeIfAbsent(s.getProjectId(), k -> new ArrayList<>()).add(s);
        }

        Map<Long, Long> activityIds = new LinkedHashMap<>();
        Map<Long, RowDto> written = new HashMap<>();
        for (Map.Entry<Long, List<Segment>> e : todo.entrySet()) {
            Long pid = e.getKey();
            String basis = "EN".equals(projectOf.get(pid).getTranslateSourceLang()) ? "EN_FIRST" : "CN_ONLY";
            List<DeepLTranslator.Item> items = e.getValue().stream()
                    .map(s -> new DeepLTranslator.Item(String.valueOf(s.getId()), s.getSrcText(),
                            EmptyMarkers.isEmpty(s.getEnText()) ? null : s.getEnText().trim()))
                    .toList();
            // 번역 호출은 트랜잭션 밖에서 — 몇 초씩 DB 연결을 붙잡지 않게 한다
            Map<String, Outcome> got = quick.translateMany(game, items, basis, req.engine(), instructions);
            Long requested = req.activityIds() == null ? null : req.activityIds().get(pid);
            tx.executeWithoutResult(status -> {
                Long activityId = null;
                for (Segment old : e.getValue()) {
                    Outcome o = got.get(String.valueOf(old.getId()));
                    if (o == null) { skipped.add(new Skip(old.getId(), "번역 결과 없음")); continue; }
                    // 번역을 기다리는 사이에 누가 확정·제외했으면 쓰지 않는다
                    Segment s = segments.findById(old.getId()).orElse(null);
                    String why = s == null ? "번역 결과 없음" : reason(s);
                    if (why != null) { skipped.add(new Skip(old.getId(), why)); continue; }
                    if (activityId == null) activityId = activityFor(pid, requested, by, req.engine());
                    rows.write(s, o.ko(), by, req.engine() == Engine.DEEPL ? TranslationRevision.Author.DEEPL : TranslationRevision.Author.LLM,
                            o.problem(), activityId);
                    written.put(s.getId(), workspace.row(s));
                }
                if (activityId != null) {
                    activityIds.put(pid, activityId);
                    events.refresh(pid, by);
                }
            });
        }
        // 요청에 있던 activity 가운데 이번에 안 쓴 것도 돌려준다 — 프론트는 응답 맵을 그대로 다음 묶음에 넘긴다
        if (req.activityIds() != null) req.activityIds().forEach((pid, aid) -> { if (pid != null && aid != null) activityIds.putIfAbsent(pid, aid); });
        List<RowDto> out = new ArrayList<>();
        for (Long id : ids) if (written.containsKey(id)) out.add(written.get(id));
        return new BatchTranslateResult(out, skipped, activityIds);
    }

    private static String reason(Segment s) { return SegmentBatch.lockedReason(s); }

    /** 요청이 준 id가 이 프로젝트의 여러 행 RETRANSLATE 항목이면 이어 쓰고, 아니면(없음·다른 파일·다른 종류·한 행 번역 항목) 새로 만든다. */
    private Long activityFor(Long projectId, Long requested, Long by, Engine engine) {
        if (requested != null) {
            Activity a = activityRepo.findById(requested).orElse(null);
            if (a != null && projectId.equals(a.getProjectId()) && a.getKind() == Activity.Kind.RETRANSLATE
                    && a.getDetail().get("segmentId") == null) return a.getId();
        }
        return activities.start(projectId, Activity.Kind.RETRANSLATE, by, Map.of("engine", engine.name()));
    }
}
