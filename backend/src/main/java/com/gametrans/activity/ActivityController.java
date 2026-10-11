package com.gametrans.activity;

import com.gametrans.activity.ActivityDtos.FeedResponse;
import com.gametrans.activity.ActivityDtos.RevertResult;
import com.gametrans.activity.ActivityDtos.RowsResponse;
import com.gametrans.config.Paging;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 변경 내역 읽기(설계 §3). 검토자 헤더는 읽지 않는다 — 워크스페이스 목록과 같이 누구나 본다. */
@RestController
@RequestMapping("/api")
public class ActivityController {
    static final int FEED_DEFAULT = 50;
    static final int ROWS_DEFAULT = 20;

    private final ActivityService service;

    public ActivityController(ActivityService service) { this.service = service; }

    @GetMapping("/projects/{id}/activity")
    public FeedResponse feed(@PathVariable Long id, @RequestParam(required = false) Long before, @RequestParam(required = false) Integer size) {
        return service.feed(id, before, size == null ? FEED_DEFAULT : size);
    }

    /** 그 항목이 건드린 행만 이전 본문으로 되돌린다(전파 없음). 되돌리기도 REVERT 항목으로 남는다. */
    @PostMapping("/activity/{id}/revert")
    public RevertResult revert(@PathVariable Long id, @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return service.revert(id, reviewerId);
    }

    @GetMapping("/activity/{id}/rows")
    public RowsResponse rows(@PathVariable Long id, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.rows(id, Paging.page(page), size == null ? ROWS_DEFAULT : Paging.size(size));
    }
}
