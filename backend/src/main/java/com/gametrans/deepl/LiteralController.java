package com.gametrans.deepl;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 구절의 DeepL 직역. 추천 창의 표기 갈림 탭이 보이는 카드의 원문을 묶어 묻는다. 키가 없으면 빈 결과. */
@RestController
@RequestMapping("/api")
public class LiteralController {
    /** 한 요청에 물을 수 있는 원문 수. 탭 하나의 카드 수(수백 개)를 넉넉히 넘는다. */
    static final int MAX_TEXTS = 1000;

    private final DeepLClient deepl;

    public LiteralController(DeepLClient deepl) { this.deepl = deepl; }

    public record LiteralRequest(List<String> texts) {}

    @PostMapping("/glossary/literal")
    public Map<String, String> literal(@RequestBody LiteralRequest req) {
        List<String> texts = req.texts() == null ? List.of() : req.texts();
        if (texts.size() > MAX_TEXTS) throw new IllegalArgumentException("한 번에 " + MAX_TEXTS + "개까지 물을 수 있습니다.");
        return deepl.literal(texts);
    }
}
