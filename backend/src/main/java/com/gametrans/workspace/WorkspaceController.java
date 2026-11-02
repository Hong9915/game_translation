package com.gametrans.workspace;

import com.gametrans.config.Paging;
import com.gametrans.review.TermMissService;
import com.gametrans.workspace.WorkspaceDtos.RowFilter;
import com.gametrans.workspace.WorkspaceDtos.RowsResponse;
import com.gametrans.workspace.WorkspaceDtos.SearchIn;
import com.gametrans.workspace.WorkspaceDtos.Summary;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/review/{projectId}")
public class WorkspaceController {
    private final WorkspaceService service;
    private final TermMissService termMiss;
    private final com.gametrans.review.ReviewMarkService marks;
    private final RowHistoryService history;
    private final SheetScopes scopes;

    public WorkspaceController(WorkspaceService service, TermMissService termMiss, com.gametrans.review.ReviewMarkService marks,
                               RowHistoryService history, SheetScopes scopes) {
        this.scopes = scopes;
        this.service = service;
        this.history = history;
        this.termMiss = termMiss;
        this.marks = marks;
    }

    public record RecomputeRequest(String sheet) {}

    @GetMapping("/rows")
    public RowsResponse rows(@PathVariable Long projectId,
                             @RequestParam(required = false) String sheet,
                             @RequestParam(required = false) RowFilter filter,
                             @RequestParam(required = false) String q,
                             @RequestParam(required = false) SearchIn qIn,
                             @RequestParam(required = false) Integer page,
                             @RequestParam(required = false) Integer size,
                             @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return service.rows(scopes.single(projectId, sheet), filter, q, qIn, reviewerId, Paging.page(page), size == null ? 500 : Paging.size(size));
    }

    @GetMapping("/rows/summary")
    public Summary summary(@PathVariable Long projectId, @RequestParam(required = false) String sheet) {
        return service.summary(projectId, sheet);
    }

    /** 행 하나의 변경 내역, 최신 판부터(우클릭 "변경 내역 보기"). */
    @GetMapping("/rows/{segmentId}/history")
    public java.util.List<RowHistoryService.Entry> history(@PathVariable Long projectId, @PathVariable Long segmentId) {
        return history.history(projectId, segmentId);
    }

    /** 머리글 더블클릭 일괄 체크. 목록과 같은 조건(시트·필터·검색·쪽)의 행을 한꺼번에 체크·해제하고 바꾼 행 수를 돌려준다. */
    @PostMapping("/marks")
    public Map<String, Integer> marks(@PathVariable Long projectId, @RequestBody com.gametrans.review.ReviewMarkService.BulkMarksRequest body,
                                      @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return Map.of("updated", marks.bulk(projectId, body, reviewerId));
    }

    /** 시트 하나의 용어 불일치를 지금 단어장으로 다시 계산한다. 바뀐 행 수를 돌려준다. */
    @PostMapping("/term-miss/recompute")
    public Map<String, Integer> recomputeTermMiss(@PathVariable Long projectId,
                                                  @RequestBody(required = false) RecomputeRequest body,
                                                  @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return Map.of("count", termMiss.recompute(projectId, body == null ? null : body.sheet()));
    }
}
