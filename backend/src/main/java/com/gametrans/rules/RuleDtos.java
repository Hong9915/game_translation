package com.gametrans.rules;

import java.time.Instant;
import java.util.List;

public final class RuleDtos {
    private RuleDtos() {}

    public record RuleDto(long id, String type, String of, String nf, Character textKind, String anchor, List<String> anchors,
                          int fixed, int left, int candidates, double ratio, double sameAsHuman, boolean flipped,
                          String status, String verdict, int converted, int kept, int appliedCount, Instant lastEdit) {}
    public record RuleList(Instant learnedAt, int unjudgedRows, double estimateUsd, List<RuleDto> rules) {}
    public record PolicyRequest(String policy) {}
    public record PreviewRow(long segmentId, long projectId, String fileName, String sheet, int rowNo, String src,
                             String before, String after, boolean complete, boolean checked) {}
    public record Preview(List<PreviewRow> rows, List<PreviewRow> excluded) {}
    public record Item(long segmentId, String before, String after) {}
    public record ApplyRequest(List<Item> checked, List<Long> unchecked) {}
    public record ApplyResult(int applied, int skippedChanged) {}
}
