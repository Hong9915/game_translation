package com.gametrans.export;

import java.util.List;

public final class ExportDtos {
    private ExportDtos() {}

    public record ExportOptions(boolean addFlagColumn, boolean addHistorySheet, boolean addGlossarySheet, Double ratio) {}
    public record ColumnHeader(String letter, String text, String kind) {}
    /** 열 쌍 하나가 쓰는 한국어 열. newColumn이면 header 이름으로 맨 끝에 새로 붙는다. */
    public record KoColumn(String letter, boolean newColumn, String header) {}
    /**
     * sheetName·koColumns·headers·rows는 고른 시트 하나(기본: 첫 설정 시트)의 것이고,
     * filledRows/totalRows/flaggedRows는 내보낼 파일 전체, 즉 설정된 모든 시트를 합한 수다.
     * sheets는 설정된 시트 이름을 설정 순으로, otherSheetCount는 손대지 않는 나머지 시트 수다.
     */
    public record ExportPreview(String fileName, String outputFileName, String sheetName, List<String> sheets, int otherSheetCount,
                                List<KoColumn> koColumns,
                                long filledRows, long totalRows, long flaggedRows,
                                List<ColumnHeader> headers, List<List<String>> rows) {}
    public record ExportFile(String fileName, byte[] bytes) {}
    public record ExportVersionDto(Long id, int version, String fileName, int filledRows, int flaggedRows, java.time.Instant createdAt) {
        public static ExportVersionDto of(ExportVersion v) {
            return new ExportVersionDto(v.getId(), v.getVersion(), v.getFileName(), v.getFilledRows(), v.getFlaggedRows(), v.getCreatedAt());
        }
    }
}
