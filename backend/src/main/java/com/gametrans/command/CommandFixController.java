package com.gametrans.command;

import com.gametrans.command.CommandFixService.FixRequest;
import com.gametrans.command.CommandFixService.FixResult;
import com.gametrans.command.CommandFixService.Plan;
import com.gametrans.command.CommandFixService.PlanRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 명령으로 고치기(2026-10-08): 리뷰 화면 창이 명령을 미리 보고(plan) 고른 행을 고친다. */
@RestController
@RequestMapping("/api/review/{projectId}/command-fix")
public class CommandFixController {
    private final CommandFixService service;

    public CommandFixController(CommandFixService service) { this.service = service; }

    @PostMapping("/plan")
    public Plan plan(@PathVariable Long projectId, @RequestBody PlanRequest body) {
        return service.plan(projectId, body);
    }

    @PostMapping
    public FixResult fix(@PathVariable Long projectId, @RequestBody FixRequest body,
                         @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return service.fix(projectId, body, reviewerId);
    }
}
