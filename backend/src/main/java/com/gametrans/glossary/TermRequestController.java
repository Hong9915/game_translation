package com.gametrans.glossary;

import com.gametrans.glossary.TermRequestDtos.ApproveRequest;
import com.gametrans.glossary.TermRequestDtos.ApproveResult;
import com.gametrans.glossary.TermRequestDtos.RequestDto;
import com.gametrans.glossary.TermRequestDtos.RequestSummary;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 단어장 요청양식 올리기·조회·GPT 초안·승인 API. */
@RestController
@RequestMapping("/api")
public class TermRequestController {
    private final TermRequestService service;

    public TermRequestController(TermRequestService service) { this.service = service; }

    @PostMapping("/games/{gameId}/glossary/requests")
    public RequestDto upload(@PathVariable Long gameId, @RequestParam("file") MultipartFile file,
                             @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return service.create(gameId, file, reviewerId);
    }

    @GetMapping("/games/{gameId}/glossary/requests")
    public List<RequestSummary> list(@PathVariable Long gameId) { return service.list(gameId); }

    @GetMapping("/glossary/requests/{id}")
    public RequestDto get(@PathVariable Long id) { return service.get(id); }

    /** GPT 초안을 기다리거나 실패한 항목의 초안을 만든다. */
    @PostMapping("/glossary/requests/{id}/generate")
    public RequestDto generate(@PathVariable Long id) { return service.generate(id); }

    @PostMapping("/glossary/requests/{id}/approve")
    public ApproveResult approve(@PathVariable Long id, @RequestBody ApproveRequest req,
                                 @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return service.approve(id, req, reviewerId);
    }
}
