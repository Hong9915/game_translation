package com.gametrans.llm;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UsageController {
    private final LlmUsageService usage;

    public UsageController(LlmUsageService usage) { this.usage = usage; }

    @GetMapping("/api/projects/{id}/usage")
    public LlmUsageService.UsageSummary project(@PathVariable Long id) { return usage.project(id); }

    @GetMapping("/api/usage")
    public LlmUsageService.UsageSummary all() { return usage.all(); }
}
