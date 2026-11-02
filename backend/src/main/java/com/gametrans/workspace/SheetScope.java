package com.gametrans.workspace;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 리뷰 범위(설계 2026-10-12): 한 게임 안의 (프로젝트, 시트) 목록. 파일 화면은 시트 하나, 게임 화면은 고른 시트들이다.
 * sheets는 늘 게임 순서(파일 업로드 순 → 시트 설정 순)이고 비어 있지 않다. 만드는 곳은 SheetScopes뿐이다.
 * finalReview는 범위의 레인 — 범위 안 시트는 모두 같은 레인이다. 같은 원문 묶음 쿼리가 이 레인끼리만 본다.
 */
public record SheetScope(Long gameId, List<SheetRef> sheets, boolean finalReview) {
    /**
     * configId는 정규화 설정 id — 설정 없이 세그먼트만 남은 시트(파일 화면에서만 열림)면 null.
     * finalReview는 그 시트의 레인(설계 2026-10-17): 설정의 열 짝에 수정 한국어 열이 있으면 최종 리뷰.
     */
    public record SheetRef(Long configId, Long projectId, String sheet, boolean finalReview) {
        /** 네이티브 쿼리가 (project_id, sheet_name)을 한 문자열로 맞춰 볼 때 쓰는 키. SegmentRepository.SCOPE_KEY와 같은 꼴. */
        public String key() { return projectId + "\u001e" + sheet; }
    }

    public List<Long> projectIds() { return List.copyOf(new LinkedHashSet<>(sheets.stream().map(SheetRef::projectId).toList())); }

    /** 범위 키를 \u001f로 이은 한 문자열(네이티브 쿼리의 string_to_array 입력). 순서가 곧 정렬 순서다. */
    public String joinedKeys() { return String.join("\u001f", sheets.stream().map(SheetRef::key).toList()); }

    public boolean singleProject() { return projectIds().size() == 1; }
}
