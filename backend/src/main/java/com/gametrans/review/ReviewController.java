package com.gametrans.review;

import com.gametrans.job.JobDtos.JobDto;
import com.gametrans.review.ReviewDtos.AssignRequest;
import com.gametrans.review.ReviewDtos.AssignResult;
import com.gametrans.review.ReviewDtos.DoneUpdate;
import com.gametrans.review.ReviewDtos.ReviewProgress;
import com.gametrans.review.ReviewDtos.SegmentFilter;
import com.gametrans.review.ReviewDtos.SegmentRow;
import com.gametrans.review.ReviewDtos.SegmentsResponse;
import com.gametrans.review.ReviewDtos.FixTermsRequest;
import com.gametrans.review.ReviewDtos.ShortenRequest;
import com.gametrans.review.ReviewDtos.TranslationUpdate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ReviewController {
    private final ReviewService service;
    private final AssignmentService assignments;
    private final ReviewMarkService marks;

    public ReviewController(ReviewService service, AssignmentService assignments, ReviewMarkService marks) {
        this.service = service;
        this.assignments = assignments;
        this.marks = marks;
    }

    @GetMapping("/projects/{id}/segments")
    public SegmentsResponse segments(@PathVariable Long id,
                                     @RequestParam(required = false) Boolean flagged,
                                     @RequestParam(required = false) Boolean proof,
                                     @RequestParam(required = false) Boolean proofPending,   // 교정 판정 대기 행만
                                     @RequestParam(required = false) String sheet,
                                     @RequestParam(required = false) String assignee,   // 검토자 id 또는 "none"
                                     @RequestParam(required = false) Boolean done,
                                     @RequestParam(required = false) Double ratio,
                                     @RequestParam(required = false) Integer limit,   // 예전 파라미터: size가 없을 때만 쓴다
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size) {
        int effective = size != null ? com.gametrans.config.Paging.size(size) : limit != null ? com.gametrans.config.Paging.size(limit) : 500;
        return service.segments(id, new SegmentFilter(flagged, Boolean.TRUE.equals(proof), sheet, assignee, done, Boolean.TRUE.equals(proofPending)), ratio,
                com.gametrans.config.Paging.page(page), effective);
    }

    @PutMapping("/segments/{sid}/translation")
    public ReviewDtos.SaveResult save(@PathVariable Long sid, @RequestBody TranslationUpdate body,
                           @RequestParam(required = false) Double ratio,
                           @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return service.saveHuman(sid, body.text(), ratio, reviewerId);
    }

    @PostMapping("/projects/{id}/shorten")
    public JobDto shorten(@PathVariable Long id, @RequestBody(required = false) ShortenRequest body,
                          @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return service.startShorten(id, body == null ? null : body.ratio(), body == null ? null : body.sheet(), reviewerId);
    }

    @PostMapping("/projects/{id}/fix-terms")
    public JobDto fixTerms(@PathVariable Long id, @RequestBody(required = false) FixTermsRequest body,
                           @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return body == null ? service.startFixTerms(id, null, null, null, reviewerId)
                            : service.startFixTerms(id, body.ratio(), body.sheet(), body.segmentIds(), reviewerId);
    }

    @PostMapping("/projects/{id}/assign")
    public AssignResult assign(@PathVariable Long id, @RequestBody AssignRequest body,
                               @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return assignments.assign(id, body, reviewerId);
    }

    @PutMapping("/segments/{sid}/done")
    public SegmentRow done(@PathVariable Long sid, @RequestBody DoneUpdate body,
                           @RequestParam(required = false) Double ratio,
                           @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return assignments.done(sid, body.done(), reviewerId, ratio);
    }

    /** 검수 체크·비고. 본문에 준 칸만 바꾼다. 검수 그리드 한 행으로 돌려준다. */
    @PutMapping("/segments/{sid}/marks")
    public com.gametrans.workspace.WorkspaceDtos.RowDto marks(@PathVariable Long sid, @RequestBody ReviewMarkService.MarksRequest body,
                                                               @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return marks.update(sid, body, reviewerId);
    }

    @GetMapping("/projects/{id}/review-progress")
    public ReviewProgress progress(@PathVariable Long id, @RequestParam(required = false) String sheet) { return assignments.progress(id, sheet); }
}
