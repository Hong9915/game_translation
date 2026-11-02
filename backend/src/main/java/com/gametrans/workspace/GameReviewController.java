package com.gametrans.workspace;

import com.gametrans.activity.ActivityDtos;
import com.gametrans.activity.ActivityService;
import com.gametrans.command.CommandFixService;
import com.gametrans.command.CommandPresetService;
import com.gametrans.common.NotFoundException;
import com.gametrans.job.JobEventBus;
import com.gametrans.review.PresenceController;
import com.gametrans.review.PresenceService;
import com.gametrans.reviewer.Reviewer;
import com.gametrans.reviewer.ReviewerService;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.gametrans.config.Paging;
import com.gametrans.josa.JosaService;
import com.gametrans.josa.TagSpacingService;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.review.ReviewMarkService;
import com.gametrans.review.TermMissService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.gametrans.workspace.WorkspaceDtos.RowFilter;
import com.gametrans.workspace.WorkspaceDtos.RowsResponse;
import com.gametrans.workspace.WorkspaceDtos.SearchIn;
import com.gametrans.workspace.WorkspaceDtos.Summary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 게임 전체 리뷰(설계 2026-10-12). 파일 화면(/api/review/{projectId})과 같은 서비스를 범위만 바꿔 부른다.
 * 범위는 쿼리 sheets=설정 id 목록(쉼표), 비우면 게임의 설정 시트 전부.
 */
@RestController
@RequestMapping("/api/games/{gameId}/review")
public class GameReviewController {
    private final WorkspaceService service;
    private final SheetScopes scopes;
    private final ReviewMarkService marks;
    private final TermMissService termMiss;
    private final JosaService josa;
    private final TagSpacingService tagSpacing;
    private final SegmentRepository segments;
    private final CommandFixService commandFix;
    private final ActivityService activities;
    private final JobEventBus bus;
    private final PresenceService presence;
    private final ReviewerService reviewers;
    private final CommandPresetService presetService;

    public GameReviewController(WorkspaceService service, SheetScopes scopes, ReviewMarkService marks, TermMissService termMiss,
                                JosaService josa, TagSpacingService tagSpacing, SegmentRepository segments, CommandFixService commandFix,
                                ActivityService activities, JobEventBus bus, PresenceService presence, ReviewerService reviewers,
                                CommandPresetService presetService) {
        this.presetService = presetService;
        this.commandFix = commandFix;
        this.activities = activities;
        this.bus = bus;
        this.presence = presence;
        this.reviewers = reviewers;
        this.service = service;
        this.scopes = scopes;
        this.marks = marks;
        this.termMiss = termMiss;
        this.josa = josa;
        this.tagSpacing = tagSpacing;
        this.segments = segments;
    }

    @GetMapping("/rows")
    public RowsResponse rows(@PathVariable Long gameId, @RequestParam(required = false) String sheets,
                             @RequestParam(required = false) RowFilter filter,
                             @RequestParam(required = false) String q,
                             @RequestParam(required = false) SearchIn qIn,
                             @RequestParam(required = false) Integer page,
                             @RequestParam(required = false) Integer size,
                             @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return service.rows(scope(gameId, sheets), filter, q, qIn, reviewerId, Paging.page(page), size == null ? 500 : Paging.size(size));
    }

    @GetMapping("/rows/summary")
    public Summary summary(@PathVariable Long gameId, @RequestParam(required = false) String sheets) {
        return service.summary(scope(gameId, sheets));
    }

    /** 머리글 일괄 체크. 목록과 같은 조건(범위·필터·검색·쪽)의 행, ids가 있으면 그 가운데 범위 안 행만. */
    @PostMapping("/marks")
    public Map<String, Integer> marks(@PathVariable Long gameId, @RequestParam(required = false) String sheets,
                                      @RequestBody ReviewMarkService.BulkMarksRequest body,
                                      @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return Map.of("updated", marks.bulk(scope(gameId, sheets), body, reviewerId));
    }

    @PostMapping("/term-miss/recompute")
    public Map<String, Integer> recomputeTermMiss(@PathVariable Long gameId, @RequestParam(required = false) String sheets) {
        return Map.of("count", termMiss.recompute(scope(gameId, sheets)));
    }

    @GetMapping("/josa/preview")
    public JosaService.Preview josaPreview(@PathVariable Long gameId, @RequestParam(required = false) String sheets) {
        return josa.preview(scope(gameId, sheets));
    }

    @PostMapping("/josa/apply")
    public JosaService.ApplyResult josaApply(@PathVariable Long gameId, @RequestParam(required = false) String sheets,
                                             @RequestBody JosaService.ApplyRequest body,
                                             @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return josa.apply(scope(gameId, sheets), body, reviewerId);
    }

    /** 무시는 행 하나라 범위 대신 그 행이 이 게임 것인지만 본다. */
    @PostMapping("/josa/ignore")
    public ResponseEntity<Void> josaIgnore(@PathVariable Long gameId, @RequestBody JosaService.IgnoreRequest body) {
        josa.ignore(projectOfSegmentInGame(gameId, body.segmentId()), body.segmentId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/tag-spacing")
    public List<TagSpacingService.Row> tagSpacing(@PathVariable Long gameId, @RequestParam(required = false) String sheets) {
        return tagSpacing.candidates(scope(gameId, sheets));
    }

    @PostMapping("/tag-spacing")
    public TagSpacingService.FixResult fixTagSpacing(@PathVariable Long gameId, @RequestParam(required = false) String sheets,
                                                     @RequestBody TagSpacingService.FixRequest body,
                                                     @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return tagSpacing.fix(scope(gameId, sheets), body, reviewerId);
    }

    @PostMapping("/command-fix/plan")
    public CommandFixService.Plan commandPlan(@PathVariable Long gameId, @RequestParam(required = false) String sheets,
                                              @RequestBody CommandFixService.PlanRequest body) {
        return commandFix.plan(scope(gameId, sheets), body);
    }

    @PostMapping("/command-fix")
    public CommandFixService.FixResult commandFix(@PathVariable Long gameId, @RequestParam(required = false) String sheets,
                                                  @RequestBody CommandFixService.FixRequest body,
                                                  @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return commandFix.fix(scope(gameId, sheets), body, reviewerId);
    }

    @GetMapping("/command-presets")
    public List<CommandPresetService.PresetDto> commandPresets(@PathVariable Long gameId) { return presetService.listForGame(gameId); }

    @PostMapping("/command-presets")
    public CommandPresetService.PresetDto saveCommandPreset(@PathVariable Long gameId, @RequestBody CommandPresetService.CreateRequest body,
                                                            @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return presetService.createForGame(gameId, body, reviewerId);
    }

    @DeleteMapping("/command-presets/{presetId}")
    public ResponseEntity<Void> deleteCommandPreset(@PathVariable Long gameId, @PathVariable Long presetId) {
        presetService.deleteForGame(gameId, presetId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/activity")
    public ActivityDtos.FeedResponse activity(@PathVariable Long gameId, @RequestParam(required = false) String sheets,
                                              @RequestParam(required = false) Long before, @RequestParam(required = false) Integer size) {
        return activities.feedForGame(gameId, scope(gameId, sheets).projectIds(), before, size == null ? com.gametrans.activity.ActivityController.FEED_DEFAULT : size);
    }

    /** 게임 화면의 실시간 스트림 하나. 파일 이벤트가 그대로 실려 온다(ReviewEvents.send). */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long gameId) {
        scopes.gameSheets(gameId);   // 없는 게임이면 빈 목록 — 구독만 생기고 아무것도 오지 않는다
        return bus.subscribeGame(gameId);
    }

    /** 게임 화면 접속자 heartbeat. 파일 화면 접속자와 섞지 않는다(PresenceService.gameRoom). */
    @PostMapping("/presence")
    public PresenceController.PresenceResponse touch(@PathVariable Long gameId, @RequestBody(required = false) PresenceController.PresenceUpdate body,
                                                     @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        Long room = PresenceService.gameRoom(gameId);
        Reviewer who = reviewers.resolve(reviewerId);
        if (who == null) return new PresenceController.PresenceResponse(presence.snapshot(room));
        return new PresenceController.PresenceResponse(presence.touch(room, who, body == null ? null : body.sheet(),
                body == null ? null : body.editingSegmentId(), Instant.now()));
    }

    @DeleteMapping("/presence")
    public ResponseEntity<Void> leave(@PathVariable Long gameId, @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        if (reviewerId != null) presence.leave(PresenceService.gameRoom(gameId), reviewerId);
        return ResponseEntity.noContent().build();
    }

    Long projectOfSegmentInGame(Long gameId, Long segmentId) {
        Long pid = segmentId == null ? null : segments.findById(segmentId).map(com.gametrans.normalize.Segment::getProjectId).orElse(null);
        if (pid == null || !gameId.equals(scopes.project(pid).getGame().getId())) throw new NotFoundException("행을 찾을 수 없습니다: " + segmentId);
        return pid;
    }

    SheetScope scope(Long gameId, String sheets) { return scopes.ofGame(gameId, SheetScopes.parseIds(sheets)); }
}
