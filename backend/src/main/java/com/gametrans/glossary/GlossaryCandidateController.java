package com.gametrans.glossary;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 단어장 후보(승인 대기) 목록·승인·버림. 승인 권한은 나누지 않는다 — 아무 검수자나. */
@RestController
@RequestMapping("/api")
public class GlossaryCandidateController {
    private final GlossaryCandidateService service;

    public GlossaryCandidateController(GlossaryCandidateService service) { this.service = service; }

    @GetMapping("/games/{gameId}/glossary/candidates")
    public List<GlossaryDtos.CandidateDto> pending(@PathVariable Long gameId) { return service.pending(gameId); }

    @PostMapping("/glossary/candidates/{id}/approve")
    public GlossaryDtos.ApproveCandidateResult approve(@PathVariable Long id,
                                                       @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return service.approve(id, reviewerId);
    }

    @PostMapping("/glossary/candidates/{id}/dismiss")
    public GlossaryDtos.CandidateDto dismiss(@PathVariable Long id,
                                             @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return service.dismiss(id, reviewerId);
    }

    @ExceptionHandler(GlossaryCandidateDecidedException.class)
    public ResponseEntity<Map<String, Object>> decided(GlossaryCandidateDecidedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage(), "candidate", e.getCandidate()));
    }
}
