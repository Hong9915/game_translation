package com.gametrans.export;

import com.gametrans.common.NotFoundException;
import com.gametrans.export.ExportDtos.ColumnHeader;
import com.gametrans.export.ExportDtos.ExportFile;
import com.gametrans.export.ExportDtos.ExportVersionDto;
import com.gametrans.export.ExportDtos.ExportOptions;
import com.gametrans.export.ExportDtos.ExportPreview;
import com.gametrans.memory.MemoryEntry;
import com.gametrans.memory.MemoryService;
import com.gametrans.normalize.NormalizeConfig;
import com.gametrans.normalize.NormalizeService;
import com.gametrans.project.Project;
import com.gametrans.project.ProjectService;
import com.gametrans.review.ReviewDtos.RevisionDto;
import com.gametrans.review.ReviewDtos.SegmentRow;
import com.gametrans.review.ReviewService;
import com.gametrans.storage.FileStorage;
import com.gametrans.term.Term;
import com.gametrans.term.TermService;
import com.gametrans.text.EmptyMarkers;
import com.gametrans.xlsx.CsvWorkbook;
import com.gametrans.xlsx.WorkbookInspector;
import com.gametrans.xlsx.XlsxExporter;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Service;

@Service
public class ExportService {
    static final int PREVIEW_ROWS = 4;

    private final ProjectService projects;
    private final NormalizeService normalize;
    private final ReviewService review;
    private final TermService terms;
    private final WorkbookInspector inspector;
    private final XlsxExporter exporter;
    private final FileStorage storage;
    private final ExportVersionRepository versions;
    private final MemoryService memory;
    private final com.gametrans.normalize.SegmentRepository segments;

    public ExportService(ProjectService projects, NormalizeService normalize, ReviewService review, TermService terms,
                         WorkbookInspector inspector, XlsxExporter exporter, FileStorage storage,
                         ExportVersionRepository versions, MemoryService memory, com.gametrans.normalize.SegmentRepository segments) {
        this.segments = segments;
        this.memory = memory;
        this.versions = versions;
        this.projects = projects;
        this.normalize = normalize;
        this.review = review;
        this.terms = terms;
        this.inspector = inspector;
        this.exporter = exporter;
        this.storage = storage;
    }

    /** sheetName이 null이면 첫 설정 시트. 표 미리보기는 그 시트만, 집계는 설정된 모든 시트를 합한 값이다. */
    public ExportPreview preview(Long projectId, String sheetName, Double ratioParam) {
        Project p = projects.get(projectId);
        List<NormalizeConfig> cfgs = configs(projectId);
        NormalizeConfig cfg = select(cfgs, sheetName);
        double ratio = ratioParam == null ? review.defaultRatio() : ratioParam;
        byte[] original = projects.originalBytes(p);
        List<SegmentRow> rows = review.rows(projectId, ratio);
        XlsxExporter.Layout layout = exporter.layout(original, cfg);  // 워크북 한 번만 연다
        List<Integer> koIdxs = layout.koIdxs();
        int flagIdx = layout.flagIdx();
        List<String> raw = layout.headers();

        java.util.Map<Integer, Integer> pairByKoIdx = new java.util.HashMap<>();
        for (int i = 0; i < koIdxs.size(); i++) pairByKoIdx.put(koIdxs.get(i), i);
        List<ColumnHeader> headers = new ArrayList<>();
        List<ExportDtos.KoColumn> koColumns = new ArrayList<>();
        int width = flagIdx;
        for (int i = 0; i < width; i++) {
            String letter = CellReference.convertNumToColString(i);
            Integer pi = pairByKoIdx.get(i);
            if (pi != null) {
                NormalizeConfig.ColumnPair pair = cfg.getPairs().get(pi);
                headers.add(new ColumnHeader(letter, pair.isNew() ? pair.headerOrDefault() : (i < raw.size() ? raw.get(i) : ""), "KO"));
            } else headers.add(new ColumnHeader(letter, i < raw.size() ? raw.get(i) : "", "ORIGINAL"));
        }
        for (int i = 0; i < koIdxs.size(); i++) {
            NormalizeConfig.ColumnPair pair = cfg.getPairs().get(i);
            koColumns.add(new ExportDtos.KoColumn(CellReference.convertNumToColString(koIdxs.get(i)), pair.isNew(), pair.isNew() ? pair.headerOrDefault() : null));
        }
        headers.add(new ColumnHeader(CellReference.convertNumToColString(width), XlsxExporter.FLAG_HEADER, "FLAG"));
        // 미리보기는 플래그 열을 늘 보이므로 비고 열은 그 다음 자리다(내보내기에서 플래그를 끄면 한 칸 앞당겨진다).
        java.util.Map<Integer, String> sheetNotes = XlsxExporter.notesByRow(notes(projectId), cfg.getSheetName());
        if (!sheetNotes.isEmpty()) headers.add(new ColumnHeader(CellReference.convertNumToColString(width + 1), XlsxExporter.NOTE_HEADER, "NOTE"));

        // 세그먼트는 칸 단위이므로 고른 시트의 행별로 모아 한 줄에 여러 한국어 열을 채운다.
        java.util.Map<Integer, WorkbookInspector.SheetRow> byRowNo = new java.util.HashMap<>();
        for (WorkbookInspector.SheetRow sr : layout.rows()) byRowNo.put(sr.rowNo(), sr);
        java.util.Map<Integer, List<SegmentRow>> byRow = new java.util.LinkedHashMap<>();
        for (SegmentRow r : rows) {
            if (!cfg.getSheetName().equals(r.sheetName())) continue;
            byRow.computeIfAbsent(r.rowNo(), k -> new ArrayList<>()).add(r);
        }
        List<List<String>> preview = new ArrayList<>();
        for (java.util.Map.Entry<Integer, List<SegmentRow>> e : byRow.entrySet()) {
            if (preview.size() >= PREVIEW_ROWS) break;
            WorkbookInspector.SheetRow sr = byRowNo.get(e.getKey());
            String[] ko = new String[koIdxs.size()];
            boolean flagged = false;
            for (SegmentRow r : e.getValue()) { ko[r.pairIndex()] = r.currentText(); flagged |= r.flagged(); }
            List<String> cells = new ArrayList<>();
            for (int i = 0; i < width; i++) {
                Integer pi = pairByKoIdx.get(i);
                if (pi != null) cells.add(ko[pi] == null ? "" : ko[pi]);
                else cells.add(sr != null && i < sr.cells().size() ? sr.cells().get(i) : "");
            }
            cells.add(flagged ? XlsxExporter.FLAG_VALUE : "");
            if (!sheetNotes.isEmpty()) cells.add(sheetNotes.getOrDefault(e.getKey(), ""));
            preview.add(cells);
        }

        return new ExportPreview(p.getFileName(), outputName(p.getFileName(), nextVersion(projectId)), cfg.getSheetName(),
                cfgs.stream().map(NormalizeConfig::getSheetName).toList(), layout.sheetCount() - cfgs.size(),
                koColumns,
                rows.stream().filter(r -> r.currentText() != null).count(), rows.size(), rows.stream().filter(SegmentRow::flagged).count(),
                headers, preview);
    }

    public ExportFile export(Long projectId, ExportOptions opts) {
        Project p = projects.get(projectId);
        List<NormalizeConfig> cfgs = configs(projectId);
        // CSV는 시트가 하나뿐이라 여러 시트를 담을 수 없다. 조용히 한 시트만 내면 나머지를 잃은 줄 모른다.
        if (isCsv(p.getFileName()) && cfgs.size() > 1)
            throw new IllegalArgumentException("CSV 파일은 시트가 하나뿐입니다. 설정한 시트를 하나만 남기고 내보내세요.");
        double ratio = opts.ratio() == null ? review.defaultRatio() : opts.ratio();
        List<SegmentRow> rows = review.rows(projectId, ratio);

        List<XlsxExporter.ExportRow> exportRows = new ArrayList<>();
        List<XlsxExporter.HistoryRow> history = new ArrayList<>();
        java.util.Map<String, String> finalTexts = new java.util.LinkedHashMap<>();
        java.util.Map<String, NormalizeConfig> cfgBySheet = new java.util.HashMap<>();
        for (NormalizeConfig c : cfgs) cfgBySheet.put(c.getSheetName(), c);
        for (SegmentRow r : rows) {
            if (r.currentText() == null) continue;
            finalTexts.put(r.srcText(), r.currentText());
            NormalizeConfig cfg = cfgBySheet.get(r.sheetName());   // 설정에서 빠진 시트의 행은 exporter가 건너뛴다
            boolean changed = cfg != null && changed(cfg.getPairs().get(r.pairIndex()), r);
            exportRows.add(new XlsxExporter.ExportRow(r.sheetName(), r.rowNo(), r.pairIndex(), r.currentText(), r.flagged(), changed));
            for (RevisionDto rev : r.history()) history.add(new XlsxExporter.HistoryRow(r.indexLabel(), rev.version(), rev.author(), rev.text(), rev.reviewerName()));
        }
        List<XlsxExporter.GlossaryRow> glossary = new ArrayList<>();
        for (Term t : terms.confirmedTerms(projectId)) glossary.add(new XlsxExporter.GlossaryRow(t.getSrcText(), t.getType().name(), t.getConfirmedKo()));

        byte[] bytes = exporter.write(projects.originalBytes(p), cfgs, exportRows,
                new XlsxExporter.Plan(opts.addFlagColumn(), opts.addHistorySheet(), opts.addGlossarySheet()), history, glossary, notes(projectId));
        // CSV로 올린 파일은 CSV로 돌려준다. 수정 이력·용어집 시트는 CSV에 담을 수 없어 빠진다.
        if (isCsv(p.getFileName())) bytes = CsvWorkbook.toCsv(bytes, cfgs.get(0).getSheetName());
        int version = nextVersion(projectId);
        String name = outputName(p.getFileName(), version);
        String dir = p.getStoragePath().substring(0, p.getStoragePath().lastIndexOf('/'));
        String path = dir + "/export/" + name;
        storage.put(path, bytes);
        ExportVersion v = new ExportVersion();
        v.setProjectId(projectId); v.setVersion(version); v.setFileName(name); v.setStoragePath(path);
        v.setFilledRows((int) rows.stream().filter(r -> r.currentText() != null).count());
        v.setFlaggedRows((int) rows.stream().filter(SegmentRow::flagged).count());
        versions.save(v);
        // 내보낸 문장은 이 게임의 완성 번역으로 보고 번역 메모리에 넣는다(같은 원문이 있으면 덮어씀).
        memory.rememberAll(projectId, finalTexts, MemoryEntry.Source.EXPORT, true);
        projects.bumpStep(projectId, 6);
        return new ExportFile(name, bytes);
    }

    /**
     * 기존 한국어와 달라진 칸인지. 검수 그리드의 "수정됨"과 같은 기준 — 양쪽을 trim하고 N/A 같은 빈 표시는 빈 값으로 본다.
     * 기존 한국어 열이 없던 NEW 모드는 비교 대상이 없으니 달라진 것으로 치지 않는다.
     */
    static boolean changed(NormalizeConfig.ColumnPair pair, SegmentRow r) {
        if (pair.koColMode() == NormalizeConfig.KoColMode.NEW) return false;
        return !EmptyMarkers.norm(r.currentText()).equals(EmptyMarkers.norm(r.existingKo()));
    }

    private List<XlsxExporter.NoteRow> notes(Long projectId) {
        return segments.notesOf(projectId).stream()
                .map(n -> new XlsxExporter.NoteRow(n.getSheet(), n.getRowNo(), n.getPairIndex(), n.getNote())).toList();
    }

    public List<ExportVersionDto> versions(Long projectId) {
        projects.get(projectId);
        return versions.findByProjectIdOrderByVersionDesc(projectId).stream().map(ExportVersionDto::of).toList();
    }

    /** 저장해 둔 N차 수정본을 다시 내려준다. */
    public ExportFile download(Long projectId, Long versionId) {
        ExportVersion v = versions.findByIdAndProjectId(versionId, projectId)
                .orElseThrow(() -> new NotFoundException("수정본을 찾을 수 없습니다: " + versionId));
        return new ExportFile(v.getFileName(), storage.get(v.getStoragePath()));
    }

    /** 올린 원본 파일 그대로. CSV로 올렸으면 내부 xlsx를 CSV로 되돌려 준다. */
    public ExportFile original(Long projectId) {
        Project p = projects.get(projectId);
        byte[] bytes = projects.originalBytes(p);
        if (isCsv(p.getFileName())) {
            String sheet = firstConfig(projectId).map(NormalizeConfig::getSheetName)
                    .orElseGet(() -> com.gametrans.xlsx.CsvWorkbook.sheetNameFor(p.getFileName()));
            bytes = CsvWorkbook.toCsv(bytes, sheet);
        }
        return new ExportFile(p.getFileName(), bytes);
    }

    int nextVersion(Long projectId) {
        return versions.findTopByProjectIdOrderByVersionDesc(projectId).map(v -> v.getVersion() + 1).orElse(1);
    }

    /** 설정된 시트 전부, 설정 순. 내보내기는 이 시트를 모두 채운다. */
    private List<NormalizeConfig> configs(Long projectId) {
        List<NormalizeConfig> cfgs = normalize.configs(projectId);
        if (cfgs.isEmpty()) throw new NotFoundException("정규화 설정이 없습니다. 2단계를 먼저 진행하세요.");
        return cfgs;
    }

    /** 미리보기에서 고른 시트. 이름을 주지 않으면 첫 설정 시트, 설정에 없는 이름이면 거절한다. */
    private static NormalizeConfig select(List<NormalizeConfig> cfgs, String sheetName) {
        if (sheetName == null || sheetName.isBlank()) return cfgs.get(0);
        return cfgs.stream().filter(c -> c.getSheetName().equals(sheetName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("설정된 시트가 아닙니다: " + sheetName));
    }

    private java.util.Optional<NormalizeConfig> firstConfig(Long projectId) {
        return normalize.configs(projectId).stream().findFirst();
    }

    static boolean isCsv(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".csv") || lower.endsWith(".tsv");
    }

    /** 예: Words.xlsx → Words_1차수정본.xlsx, table.csv → table_2차수정본.csv */
    static String outputName(String fileName, int version) {
        String suffix = "_" + version + "차수정본";
        if (isCsv(fileName)) return fileName.substring(0, fileName.lastIndexOf('.')) + suffix + ".csv";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName + suffix + ".xlsx" : fileName.substring(0, dot) + suffix + fileName.substring(dot);
    }
}
