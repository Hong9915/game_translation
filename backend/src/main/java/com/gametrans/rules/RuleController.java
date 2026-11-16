package com.gametrans.rules;

import com.gametrans.rules.RuleDtos.PolicyRequest;
import com.gametrans.rules.RuleDtos.RuleDto;
import com.gametrans.rules.RuleDtos.RuleList;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RuleController {
    private final CorrectionRuleService service;
    private final RuleApplyService apply;
    private final TextKindService kinds;
    private final StyleRewrite rewrite;

    public RuleController(CorrectionRuleService service, RuleApplyService apply, TextKindService kinds, StyleRewrite rewrite) {
        this.service = service; this.apply = apply; this.kinds = kinds; this.rewrite = rewrite;
    }

    public record RewriteRequest(java.util.List<Long> segmentIds) {}

    @PostMapping("/rules/{id}/rewrite")
    public java.util.List<RuleDtos.PreviewRow> rewrite(@PathVariable Long id, @RequestBody RewriteRequest body) {
        return rewrite.rewrite(id, body.segmentIds());
    }

    @PostMapping("/games/{gameId}/rules/judge-kinds")
    public com.gametrans.job.JobDtos.JobDto judgeKinds(@PathVariable Long gameId) { return kinds.judge(gameId); }

    @GetMapping("/rules/{id}/preview")
    public RuleDtos.Preview preview(@PathVariable Long id, @RequestParam(required = false) Long projectId) { return apply.preview(id, projectId); }

    @PostMapping("/rules/{id}/apply")
    public RuleDtos.ApplyResult apply(@PathVariable Long id, @RequestBody RuleDtos.ApplyRequest body,
                                      @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return apply.apply(id, body, reviewerId);
    }

    @PostMapping("/games/{gameId}/rules/relearn")
    public RuleList relearn(@PathVariable Long gameId) { return service.relearn(gameId); }

    @GetMapping("/games/{gameId}/rules")
    public RuleList list(@PathVariable Long gameId) { return service.list(gameId); }

    @PostMapping("/rules/{id}/ignore")
    public RuleDto ignore(@PathVariable Long id, @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return service.setIgnored(id, true, reviewerId);
    }

    @PostMapping("/rules/{id}/unignore")
    public RuleDto unignore(@PathVariable Long id, @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return service.setIgnored(id, false, reviewerId);
    }

    @PutMapping("/games/{gameId}/style-policy/{textKind}")
    public RuleList policy(@PathVariable Long gameId, @PathVariable String textKind, @RequestBody PolicyRequest body,
                           @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return service.setPolicy(gameId, textKind.charAt(0), body.policy(), reviewerId);
    }
}
