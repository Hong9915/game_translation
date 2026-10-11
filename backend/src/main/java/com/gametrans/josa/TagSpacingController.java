package com.gametrans.josa;

import com.gametrans.josa.TagSpacingService.FixRequest;
import com.gametrans.josa.TagSpacingService.FixResult;
import com.gametrans.josa.TagSpacingService.Row;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 태그 경계 띄어쓰기(2026-10-08): 리뷰 화면의 "태그 띄어쓰기" 창이 시트 단위로 후보를 받고 고른 행을 고친다. */
@RestController
@RequestMapping("/api/review/{projectId}/tag-spacing")
public class TagSpacingController {
    private final TagSpacingService service;

    public TagSpacingController(TagSpacingService service) { this.service = service; }

    @GetMapping
    public List<Row> candidates(@PathVariable Long projectId, @RequestParam String sheet) {
        return service.candidates(projectId, sheet);
    }

    @PostMapping
    public FixResult fix(@PathVariable Long projectId, @RequestBody FixRequest body,
                         @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return service.fix(projectId, body, reviewerId);
    }
}
