package com.gametrans.translate;

import com.gametrans.translate.TranslateDtos.BatchTranslateRequest;
import com.gametrans.translate.TranslateDtos.BatchTranslateResult;
import com.gametrans.translate.TranslateDtos.PolishRequest;
import com.gametrans.translate.TranslateDtos.PolishResult;
import com.gametrans.translate.TranslateDtos.RowShortenRequest;
import com.gametrans.translate.TranslateDtos.RowShortenResult;
import com.gametrans.translate.TranslateDtos.RowTranslateRequest;
import com.gametrans.translate.TranslateDtos.SaveBatchRequest;
import com.gametrans.translate.TranslateDtos.SaveBatchResult;
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
    private final BatchTranslateService batch;
    private final PolishService polish;

    public RowTranslateController(RowTranslateService service, RowShortenService shorten, BatchTranslateService batch, PolishService polish) {
        this.service = service;
        this.shorten = shorten;
        this.batch = batch;
        this.polish = polish;
    }

    /** 고른 행(최대 50행, 한 게임)의 Fix를 GPT로 다듬은 안. 저장하지 않는다 — 고른 안은 save-batch로 넣는다. */
    @PostMapping("/api/segments/polish")
    public PolishResult polish(@RequestBody PolishRequest body) {
        return polish.polish(body == null ? null : body.segmentIds());
    }

    /** 고른 다듬기 안을 사람이 저장한 것처럼 넣는다(최대 500행, 한 게임, 한 트랜잭션). */
    @PostMapping("/api/segments/save-batch")
    public SaveBatchResult saveBatch(@RequestBody SaveBatchRequest body,
                                     @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return polish.saveBatch(body, reviewerId);
    }

    /** 고른 여러 행 다시 번역(최대 50행, 한 게임). engine은 DEEPL | GPT, activityIds는 앞 묶음 응답의 것을 그대로 넘긴다. */
    @PostMapping("/api/segments/translate-batch")
    public BatchTranslateResult translateBatch(@RequestBody BatchTranslateRequest body,
                                               @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return batch.translate(body, reviewerId);
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
