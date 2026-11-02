package com.gametrans.export;

import java.util.List;

public final class ExportDtos {
    private ExportDtos() {}

    /**
     * sheet가 있으면 그 시트만 든 파일(설계 2026-10-12 §4), 없으면 파일 전체.
     * overwriteKo면 정규화에서 "새 열로 추가"(EXISTING_TO_NEW)한 쌍도 이번 파일만 기존 한국어 열에 덮어쓴다(설정은 그대로).
     * finalOnly면 최종 리뷰 시트에서 최종 수정 표시를 켠 행만 담는다(2026-10-19). sheet와 같이 주면 그 시트만.
     */
    public record ExportOptions(boolean addFlagColumn, boolean addHistorySheet, boolean addGlossarySheet, Double ratio, String sheet, Boolean overwriteKo,
                                Boolean finalOnly, Boolean addNoteColumn) {}
    public record ColumnHeader(String letter, String text, String kind) {}
    /** 열 쌍 하나가 쓰는 한국어 열. newColumn이면 header 이름으로 맨 끝에 새로 붙는다. */
    public record KoColumn(String letter, boolean newColumn, String header) {}
    /**
     * sheetName·koColumns·headers·rows는 고른 시트 하나(기본: 첫 설정 시트)의 것이고,
     * filledRows/totalRows/flaggedRows는 내보낼 파일 전체, 즉 설정된 모든 시트를 합한 수다.
     * sheets는 설정된 시트 이름을 설정 순으로, otherSheetCount는 손대지 않는 나머지 시트 수다.
     * canOverwrite: 내보낼 시트 가운데 기존 한국어 열을 읽고 새 열에 쓰는(EXISTING_TO_NEW) 쌍이 있어 덮어쓰기를 고를 수 있는지.
     * finalMarkedRows: 최종 수정 표시를 켠 엑셀 행 수 — 내보낼 시트에 최종 리뷰 시트가 없으면 null. finalOutputFileName은 그때 받을 파일 이름.
     */
    public record ExportPreview(String fileName, String outputFileName, String sheetName, List<String> sheets, int otherSheetCount,
                                List<KoColumn> koColumns,
                                long filledRows, long totalRows, long flaggedRows,
                                List<ColumnHeader> headers, List<List<String>> rows, boolean canOverwrite,
                                Long finalMarkedRows, String finalOutputFileName) {}
    public record ExportFile(String fileName, byte[] bytes) {}
    public record ExportVersionDto(Long id, int version, String fileName, int filledRows, int flaggedRows, java.time.Instant createdAt, String sheetName) {
        public static ExportVersionDto of(ExportVersion v) {
            return new ExportVersionDto(v.getId(), v.getVersion(), v.getFileName(), v.getFilledRows(), v.getFlaggedRows(), v.getCreatedAt(), v.getSheetName());
        }
    }
}
