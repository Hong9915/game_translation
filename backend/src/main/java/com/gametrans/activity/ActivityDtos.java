package com.gametrans.activity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gametrans.reviewer.ReviewerService.ReviewerRef;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ActivityDtos {
    private ActivityDtos() {}

    /**
     * projectId: 그 행이 든 파일 — 같은 원문 전파는 게임의 다른 파일 행도 바꾸므로 피드를 보는 프로젝트와 다를 수 있다(점프는 같은 파일만).
     * before: 바로 앞 판(첫 판이면 null). ordinal: 그 시트 전체 보기에서의 0-기반 위치(점프용).
     */
    public record RowItem(Long segmentId, Long projectId, String sheet, int rowNo, long ordinal, String before, String after) {}

    /** sheet: 작업류는 detail.sheet, EDIT·RETRANSLATE는 대표 행의 시트, 나머지 null. primary: EDIT·RETRANSLATE만. */
    public record FeedItem(Long id, String kind, ReviewerRef reviewer, Instant createdAt, long rowCount, String sheet, RowItem primary,
                           Map<String, Object> detail) {}

    public record FeedResponse(List<FeedItem> items, @JsonInclude(JsonInclude.Include.NON_NULL) Long nextBefore) {}

    public record RowsResponse(List<RowItem> rows, int page, int size, long total) {}

    /** 되돌리기 결과. skipped: 그 사이 바뀐 행·2차 체크 행·첫 판 행처럼 손대지 않은 행 수. */
    public record RevertResult(int reverted, int skipped) {}
}
