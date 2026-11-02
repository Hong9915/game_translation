package com.gametrans.normalize;

import com.gametrans.xlsx.ColumnGuesser;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public final class NormalizeDtos {
    private NormalizeDtos() {}

    public record HeaderCell(String letter, String text) {}
    /** preview: 헤더 바로 아래 데이터 행 몇 줄. 정규화 화면이 열 역할을 고를 때 보여 준다. */
    public record SheetOption(String name, int rowCount, int headerRow, List<HeaderCell> headers, ColumnGuesser.Guess guess, List<List<String>> preview) {}
    public record SheetsResponse(List<SheetOption> sheets) {}

    public record PairRequest(String srcCol, String enCol, NormalizeConfig.KoColMode koColMode, String koCol, String koHeader, String fixCol) {}
    public record NormalizeRequest(
            @NotBlank String sheetName,
            @Min(1) int headerRow,
            String keyCol,
            @NotNull List<PairRequest> pairs,
            Boolean retranslateExisting,
            Boolean proofreadExisting) {}

    /**
     * kept: 기존 한국어를 그대로 살린 행(번역 대상에서 빠짐). intentional: 예전 정규화가 영어·한국어 모두 N/A라
     * 건너뛴 행(skipped에 포함) — 새로 정규화하면 0이다.
     */
    public record NormalizeStats(long totalRows, long normalized, long skipped, long withEn, long kept, long intentional) {}
    public record SegmentPreview(Long id, String indexLabel, int pairIndex, String keyText, String srcText, String enText, String existingKoText) {
        public static SegmentPreview of(Segment s) { return new SegmentPreview(s.getId(), s.getIndexLabel(), s.getPairIndex(), s.getKeyText(), s.getSrcText(), s.getEnText(), s.getExistingKoText()); }
    }
    public record ConfigDto(String sheetName, int headerRow, String keyCol, List<NormalizeConfig.ColumnPair> pairs, boolean retranslateExisting, boolean proofreadExisting) {
        public static ConfigDto of(NormalizeConfig c) {
            return new ConfigDto(c.getSheetName(), c.getHeaderRow(), c.getKeyCol(), c.getPairs(), c.isRetranslateExisting(), c.isProofreadExisting());
        }
    }
    /** 설정한 시트 하나: 그 시트의 설정과 그 시트만의 통계. */
    public record SheetConfigDto(ConfigDto config, NormalizeStats stats) {}
    /** configs는 설정한 순서, stats는 프로젝트 전체. preview/total은 sheet를 주면 그 시트만. */
    public record NormalizeResponse(List<SheetConfigDto> configs, NormalizeStats stats, List<SegmentPreview> preview, int page, int size, long total) {}
}
