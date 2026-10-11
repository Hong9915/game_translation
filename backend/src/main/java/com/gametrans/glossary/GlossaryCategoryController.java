package com.gametrans.glossary;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 표기 갈림 탭의 분류 필터. 탭이 카드의 원문·주 표기·예문을 묶어 한 번 묻는다. */
@RestController
@RequestMapping("/api")
public class GlossaryCategoryController {
    private final GlossaryCategoryService service;

    public GlossaryCategoryController(GlossaryCategoryService service) { this.service = service; }

    public record CategoryRequest(List<GlossaryCategoryService.Item> items) {}

    @PostMapping("/games/{gameId}/glossary/categories")
    public Map<String, String> categories(@PathVariable Long gameId, @RequestBody CategoryRequest req) {
        return service.categorize(gameId, req.items() == null ? List.of() : req.items());
    }
}
