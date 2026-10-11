package com.gametrans.josa;

import com.gametrans.josa.JosaService.ApplyRequest;
import com.gametrans.josa.JosaService.ApplyResult;
import com.gametrans.josa.JosaService.IgnoreRequest;
import com.gametrans.josa.JosaService.Preview;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 조사 제안(설계 2026-10-07 §5). 행 목록·요약은 WorkspaceController가 JOSA 필터로 준다. */
@RestController
@RequestMapping("/api/review/{projectId}/josa")
public class JosaController {
    private final JosaService service;

    public JosaController(JosaService service) { this.service = service; }

    @GetMapping("/preview")
    public Preview preview(@PathVariable Long projectId, @RequestParam String sheet) {
        return service.preview(projectId, sheet);
    }

    @PostMapping("/apply")
    public ApplyResult apply(@PathVariable Long projectId, @RequestBody ApplyRequest body,
                             @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return service.apply(projectId, body, reviewerId);
    }

    @PostMapping("/ignore")
    public ResponseEntity<Void> ignore(@PathVariable Long projectId, @RequestBody IgnoreRequest body) {
        service.ignore(projectId, body.segmentId());
        return ResponseEntity.noContent().build();
    }
}
