package com.gametrans.translate;

import com.gametrans.translate.TranslateDtos.QuickTranslateRequest;
import com.gametrans.translate.TranslateDtos.QuickTranslateResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games/{id}/quick-translate")
public class QuickTranslateController {
    private final QuickTranslateService service;

    public QuickTranslateController(QuickTranslateService service) { this.service = service; }

    @PostMapping
    public QuickTranslateResult translate(@PathVariable Long id, @RequestBody QuickTranslateRequest req) { return service.translate(id, req); }
}
