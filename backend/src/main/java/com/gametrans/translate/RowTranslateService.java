package com.gametrans.translate;

import com.gametrans.activity.Activity;
import com.gametrans.activity.ActivityService;
import com.gametrans.common.NotFoundException;
import com.gametrans.config.AppProperties;
import com.gametrans.deepl.DeepLTranslator;
import com.gametrans.game.Game;
import com.gametrans.game.GameService;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.Project;
import com.gametrans.project.ProjectRepository;
import com.gametrans.review.ReviewEvents;
import com.gametrans.review.ReviewService;
import com.gametrans.review.TermMissService;
import com.gametrans.reviewer.Reviewer;
import com.gametrans.reviewer.ReviewerService;
import com.gametrans.text.EmptyMarkers;
import com.gametrans.text.GlossaryCheck;
import com.gametrans.translate.TranslateDtos.QuickTranslateResult;
import com.gametrans.translate.TranslateDtos.RowTranslateRequest;
import com.gametrans.workspace.WorkspaceDtos.RowDto;
import com.gametrans.workspace.WorkspaceService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 검수 그리드의 행 하나를 GPT로 번역한다(설계 2026-09-23 §3.1). GPT 호출은 빠른 번역과 같은 경로(QuickTranslateService.translateOne)이고,
 * 결과는 LLM 리비전으로 쌓은 뒤 용어 누락을 게임의 유효 단어장으로 다시 계산해 그리드 한 행으로 돌려준다.
 * 메모리에 같은 원문이 있어도 GPT에 묻는다 — 사람이 굳이 "번역하기"를 누른 행이다.
 * 검수를 거치지 않은 GPT 문장이므로 번역 메모리에 넣지 않고, 같은 원문 행에 퍼뜨리지도 않는다(사람이 저장할 때 퍼진다).
 */
@Service
public class RowTranslateService {
    /** 행 번역의 고정 지시. 사용자가 넣은 지시·단어장 메모는 이 뒤에 붙는다. */
    static final String ROW_INSTRUCTIONS = """
        This is one row of a game's string table being reviewed.
        Write natural Korean word order instead of following the Chinese word order.
        Keep every placeholder and markup tag exactly as in the source.
        Drop vague modifiers and keep only the key words.
        For easily confused families of terms (such as buff vs. enhancement), follow the glossary notes.
        Keep the wording style the game already uses.""";

    private final SegmentRepository segments;
    private final GameService games;
    private final QuickTranslateService quick;
    private final RevisionService revisions;
    private final TermMissService termMiss;
    private final ReviewService review;
    private final WorkspaceService workspace;
    private final ReviewerService reviewers;
    private final ReviewEvents events;
    private final TransactionTemplate tx;
    private final ActivityService activities;
    private final AppProperties props;
    private final ProjectRepository projectRepo;

    /** 지금 GPT에 가 있는 세그먼트. 같은 행을 두 번 눌러 리비전이 겹쳐 쌓이지 않게 한다. */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public RowTranslateService(SegmentRepository segments, GameService games, QuickTranslateService quick, RevisionService revisions,
                               TermMissService termMiss, ReviewService review, WorkspaceService workspace, ReviewerService reviewers,
                               ReviewEvents events, TransactionTemplate tx, ActivityService activities,
                               AppProperties props, ProjectRepository projectRepo) {
        this.props = props;
        this.projectRepo = projectRepo;
        this.activities = activities;
        this.segments = segments;
        this.games = games;
        this.quick = quick;
        this.revisions = revisions;
        this.termMiss = termMiss;
        this.review = review;
        this.workspace = workspace;
        this.reviewers = reviewers;
        this.events = events;
        this.tx = tx;
    }

    public RowDto translate(Long segmentId, RowTranslateRequest req, Long reviewerId) {
        Reviewer who = reviewers.resolve(reviewerId);
        Long by = who == null ? null : who.getId();
        Segment s = find(segmentId);
        check(s);
        if (!running.add(segmentId)) throw new IllegalStateException("이 행은 이미 번역 중입니다. 끝난 뒤에 다시 시도하세요.");
        try {
            // GPT 호출은 트랜잭션 밖에서 — 몇 초씩 DB 연결을 붙잡지 않게 한다
            Game game = games.get(termMiss.gameIdOf(s.getProjectId()));
            String en = EmptyMarkers.isEmpty(s.getEnText()) ? null : s.getEnText().trim();
            boolean deepl = props.translate().deepl();
            // DeepL이면 이 파일에서 마지막으로 고른 원문 언어를 따르고, 지시는 쓰지 않는다
            String basis = deepl
                    ? ("EN".equals(projectRepo.findById(s.getProjectId()).map(Project::getTranslateSourceLang).orElse("ZH")) ? "EN_FIRST" : "CN_ONLY")
                    : (req == null ? null : req.basis());
            QuickTranslateResult r = quick.translateOne(game, s.getSrcText(), en, basis,
                    deepl ? null : instructions(s, req == null ? null : req.instructions()), false);
            TranslationRevision.Author author = deepl ? TranslationRevision.Author.DEEPL : TranslationRevision.Author.LLM;
            return tx.execute(status -> save(segmentId, r.ko(), by, author, r.problem()));
        } finally {
            running.remove(segmentId);
        }
    }

    private RowDto save(Long segmentId, String ko, Long by, TranslationRevision.Author author, String problem) {
        Segment s = find(segmentId);
        // GPT를 기다리는 사이에 누가 완료로 찍었으면 저장하지 않는다 — 일괄 변경·저장은 완료 행을 건드리지 않는다
        check(s);
        // 사람이 시킨 한 행 다시 번역은 변경 내역에 RETRANSLATE로 남긴다(설계 2026-09-30 §1).
        Long activityId = activities.start(s.getProjectId(), Activity.Kind.RETRANSLATE, by, Map.of("segmentId", segmentId));
        revisions.add(segmentId, ko, author, by, activityId);
        String miss = GlossaryCheck.format(GlossaryCheck.missing(s.getSrcText(), ko, termMiss.glossaryFor(s.getProjectId())));
        segments.updateTermMiss(segmentId, miss);
        // 벌크 update는 영속성 컨텍스트를 비켜 간다(saveHuman과 같은 사정) — 들고 있는 엔티티에 방금 쓴 값을 직접 반영해 응답·알림이 새 값을 보게 한다
        s.setTermMiss(miss);
        if (problem != null) {
            String reason = DeepLTranslator.problemNote(problem);
            segments.updateMarks(List.of(segmentId), null, null, true, null, null);
            String note = s.getNote() == null || s.getNote().isBlank() ? reason : s.getNote() + " · " + reason;
            segments.updateNote(segmentId, note);
            s.setNeedsCheck(true); s.setNote(note);   // 응답·알림이 새 값을 보게(updateTermMiss와 같은 사정)
        }
        events.segmentUpdated(s.getProjectId(), by, review.row(segmentId, review.defaultRatio()));
        return workspace.row(s);
    }

    private Segment find(Long segmentId) {
        return segments.findById(segmentId).orElseThrow(() -> new NotFoundException("세그먼트를 찾을 수 없습니다: " + segmentId));
    }

    private static void check(Segment s) {
        if (s.isSkipped()) throw new IllegalArgumentException("제외된 행은 번역하지 않습니다.");
    }

    /** 고정 지시 + 기존 한국어(있으면 참고로) + 사용자 지시. 단어장 메모는 translateOne이 이 뒤에 붙인다. */
    static String instructions(Segment s, String extra) {
        StringBuilder sb = new StringBuilder(ROW_INSTRUCTIONS);
        if (!EmptyMarkers.isEmpty(s.getExistingKoText()))
            sb.append("\nThe game's current Korean for this row (reference only; keep its established terms where they are right): ")
                    .append(s.getExistingKoText().trim());
        if (extra != null && !extra.isBlank()) sb.append('\n').append(extra.trim());
        return sb.toString();
    }
}
