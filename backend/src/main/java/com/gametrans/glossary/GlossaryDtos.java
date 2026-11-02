package com.gametrans.glossary;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

public final class GlossaryDtos {
    private GlossaryDtos() {}

    /** gameId가 null이면(공유 항목) JSON에서 아예 뺀다. ko·note·oldKo가 null이어도 필드는 포함된다. */
    public record EntryDto(Long id, @JsonInclude(JsonInclude.Include.NON_NULL) Long gameId, String kind, String matchMode, String find, String ko, String note,
                            String oldKo, boolean shared, Instant updatedAt) {}

    /** 저장 요청. 공유 여부는 여기 없다 — "공유로 올리기"는 별도 엔드포인트로만 한다. oldKo는 SRC+PARTIAL에서만 뜻이 있다. */
    public record EntryRequest(String kind, String matchMode, String find, String ko, String note, String oldKo) {}

    /** 저장·수정 결과: 저장된 항목과, 그 자리에서 바로 적용되어 바뀐 행 수. */
    public record SaveResult(EntryDto entry, int applied) {}

    public record ApplyResult(int applied) {}

    public record CountResult(int count) {}

    /** 추천 구절의 지금 번역 하나: 그 표기가 든 행 수와 예문 한 쌍(긴 문장은 잘라 보낸다). */
    public record Rendering(String ko, int rows, String exampleSrc, String exampleKo) {}

    /**
     * 단어장 추천 한 줄. sentences = 그 구절이 든 서로 다른 문장 수(숫자만 다른 행은 한 문장, 순위 기준),
     * rows = 원문에 그 구절이 든 행 수, renderings = 그 행들의 한국어에서 찾은 지금 번역(많아야 3개, 없으면 빈 목록),
     */
    public record Recommendation(String find, int sentences, int rows, java.util.List<Rendering> renderings) {}

    /** 표기 갈림 예문 한 쌍(긴 문장은 잘라 보낸다). */
    public record VariantExample(String src, String ko) {}

    /** 한 구절의 표기 하나. spacing = 주 표기와 띄어쓰기만 다른 꼴. rows = 그 표기가 든 행 수(다른 표기는 주 표기가 없는 행 가운데). */
    public record VariantRendering(String ko, int rows, boolean spacing, java.util.List<VariantExample> examples) {}

    /**
     * 표기 갈림 한 구절(설계 2026-10-07 §1). kind = ALT(다른 표기 있음) | SPACING(띄어쓰기만) | MISSING(뚜렷한 주 표기가 몇 행에만 없음).
     * rows = 원문에 find가 든 행 수, missingRows/missing = 주 표기도 다른 표기도 없는 행 수와 예문.
     * 여기와 VariantRendering의 행 수는 파일마다 되풀이된 문장까지 센 세그먼트 행 수이며 확정 행도 들 수 있다(통일은 확정 행을 건너뛴다).
     * standard = SPACING 카드에서 Kiwi 띄어쓰기 교정이 고른 꼴(주 표기·띄어쓰기 꼴 가운데 하나일 때만). 그 밖엔 null.
     */
    public record Variant(String find, String kind, int rows, VariantRendering main, java.util.List<VariantRendering> alts,
                          int missingRows, java.util.List<VariantExample> missing, String standard) {
        public Variant withStandard(String s) { return new Variant(find, kind, rows, main, alts, missingRows, missing, s); }
    }

    /** 표기 통일 요청(설계 2026-10-07 §2). replace = 행에서 ko로 바꿀 표기, keep = 그대로 둘 표기(있으면 단어장엔 메모로 남는다). */
    public record UnifyRequest(String find, String ko, java.util.List<String> replace, java.util.List<String> keep) {}

    /** 띄어쓰기 한꺼번에 통일의 한 구절: 원문에 find가 든 행에서 replace의 꼴을 ko로. replace는 ko와 공백만 달라야 한다. */
    public record SpacingItem(String find, String ko, java.util.List<String> replace) {}

    public record SpacingRequest(java.util.List<SpacingItem> items) {}
}
