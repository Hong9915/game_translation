package com.gametrans.translate;

import com.gametrans.job.JobDtos.JobDto;
import com.gametrans.translate.TranslateDtos.TranslateRequest;
import com.gametrans.translate.TranslateDtos.TranslateStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{id}/translate")
public class TranslationController {
    private final TranslationService service;

    public TranslationController(TranslationService service) { this.service = service; }

    @PostMapping
    public JobDto start(@PathVariable Long id, @RequestBody TranslateRequest req) { return service.start(id, req); }

    @GetMapping
    public TranslateStatus status(@PathVariable Long id) { return service.status(id); }
}
