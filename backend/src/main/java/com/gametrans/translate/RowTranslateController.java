package com.gametrans.translate;

import com.gametrans.translate.TranslateDtos.RowShortenRequest;
import com.gametrans.translate.TranslateDtos.RowShortenResult;
import com.gametrans.translate.TranslateDtos.RowTranslateRequest;
import com.gametrans.workspace.WorkspaceDtos.RowDto;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RowTranslateController {
    private final RowTranslateService service;
    private final RowShortenService shorten;

    public RowTranslateController(RowTranslateService service, RowShortenService shorten) {
        this.service = service;
        this.shorten = shorten;
    }

    /** 검수 그리드의 행 번역. 본문은 없어도 된다. */
    @PostMapping("/api/segments/{sid}/translate")
    public RowDto translate(@PathVariable Long sid, @RequestBody(required = false) RowTranslateRequest body,
                            @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return service.translate(sid, body, reviewerId);
    }

    /** 검수 그리드의 행 줄이기. 후보만 돌려주고 저장하지 않는다 — 고른 후보는 일반 저장(PUT translation)으로 들어간다. */
    @PostMapping("/api/segments/{sid}/shorten")
    public RowShortenResult shorten(@PathVariable Long sid, @RequestBody(required = false) RowShortenRequest body) {
        return shorten.shorten(sid, body);
    }
}
