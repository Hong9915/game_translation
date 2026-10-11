package com.gametrans.normalize;

import com.gametrans.normalize.NormalizeDtos.*;
import com.gametrans.project.Project;
import com.gametrans.project.ProjectService;
import com.gametrans.memory.MemoryEntry;
import com.gametrans.memory.MemoryService;
import com.gametrans.review.TermMissService;
import com.gametrans.text.GlossaryCheck;
import com.gametrans.text.EmptyMarkers;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import com.gametrans.xlsx.ColumnGuesser;
import com.gametrans.xlsx.WorkbookInspector;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.poi.ss.util.CellReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NormalizeService {
    private static final Logger log = LoggerFactory.getLogger(NormalizeService.class);

    private final ProjectService projects;
    private final WorkbookInspector inspector;
    private final NormalizeConfigRepository configs;
    private final SegmentRepository segments;
    private final com.gametrans.term.TermRepository terms;
    private final com.gametrans.term.TermService termService;
    private final RevisionService revisions;
    private final MemoryService memory;
    private final TermMissService termMiss;

    public NormalizeService(ProjectService projects, WorkbookInspector inspector, NormalizeConfigRepository configs,
                            SegmentRepository segments, com.gametrans.term.TermRepository terms,
                            com.gametrans.term.TermService termService, RevisionService revisions,
                            MemoryService memory, TermMissService termMiss) {
        this.termService = termService;
        this.termMiss = termMiss;
        this.memory = memory;
        this.revisions = revisions;
        this.projects = projects;
        this.inspector = inspector;
        this.configs = configs;
        this.segments = segments;
        this.terms = terms;
    }

    public SheetsResponse sheets(Long projectId, int headerRow) {
        Project p = projects.get(projectId);
        byte[] bytes = projects.originalBytes(p);
        List<SheetOption> out = new ArrayList<>();
        for (WorkbookInspector.SheetHeaders info : inspector.sheetHeaders(bytes, headerRow)) {
            List<String> raw = info.headers();
            List<HeaderCell> headers = new ArrayList<>();
            for (int i = 0; i < raw.size(); i++) headers.add(new HeaderCell(CellReference.convertNumToColString(i), raw.get(i)));
            out.add(new SheetOption(info.name(), info.rowCount(), headerRow, headers, ColumnGuesser.guess(raw), info.preview()));
        }
        return new SheetsResponse(out);
    }

    @Transactional
    public NormalizeResponse normalize(Long projectId, NormalizeRequest req) {
        Project p = projects.get(projectId);
        byte[] bytes = projects.originalBytes(p);
        List<WorkbookInspector.SheetRow> rows;
        try { rows = inspector.readRows(bytes, req.sheetName(), req.headerRow()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException(e.getMessage()); }
        List<NormalizeConfig.ColumnPair> pairs = validatePairs(req.pairs());

        NormalizeConfig cfg = configs.findByProjectIdAndSheetName(projectId, req.sheetName()).orElseGet(NormalizeConfig::new);
        cfg.setProjectId(projectId);
        cfg.setSheetName(req.sheetName());
        cfg.setHeaderRow(req.headerRow());
        cfg.setKeyCol(blankToNull(req.keyCol()));
        cfg.setPairs(pairs);
        cfg.setProofreadExisting(Boolean.TRUE.equals(req.proofreadExisting()));
        cfg.setRetranslateExisting(!cfg.isProofreadExisting() && Boolean.TRUE.equals(req.retranslateExisting()));
        configs.save(cfg);

        // 용어는 원문 기준이라 프로젝트 전체에 걸쳐 있다. 방금 저장한 것까지 세어 시트가 이 하나뿐일 때만 비운다 —
        // 시트가 여럿인데 지우면 다른 시트에서 확정한 용어까지 날아간다.
        if (configs.countByProjectId(projectId) <= 1) terms.deleteByProjectId(projectId);
        segments.deleteByProjectIdAndSheetName(projectId, cfg.getSheetName());
        segments.flush();

        Integer keyIdx = cfg.getKeyCol() == null ? null : CellReference.convertColStringToIndex(cfg.getKeyCol());
        List<Segment> created = new ArrayList<>();
        List<Segment> kept = new ArrayList<>();
        for (WorkbookInspector.SheetRow row : rows) {
            for (int pi = 0; pi < pairs.size(); pi++) {
                NormalizeConfig.ColumnPair pair = pairs.get(pi);
                int srcIdx = CellReference.convertColStringToIndex(pair.srcCol());
                Integer enIdx = pair.enCol() == null ? null : CellReference.convertColStringToIndex(pair.enCol());
                Integer koIdx = pair.koCol() == null ? null : CellReference.convertColStringToIndex(pair.koCol());
                Segment s = new Segment();
                s.setProjectId(projectId);
                s.setSheetName(cfg.getSheetName());
                s.setRowNo(row.rowNo());
                s.setPairIndex(pi);
                // 쌍이 하나면 예전처럼 시트!행, 여럿이면 시트!행!원문열 로 어느 칸인지 구분한다.
                s.setIndexLabel(cfg.getSheetName() + "!" + row.rowNo() + (pairs.size() > 1 ? "!" + pair.srcCol() : ""));
                s.setKeyText(keyIdx == null ? null : blankToNull(cell(row, keyIdx)));
                String koRaw = koIdx == null ? null : cell(row, koIdx);
                String src = srcOrNull(cell(row, srcIdx), koRaw);
                String en = enIdx == null ? null : emptyToNull(cell(row, enIdx));
                String enRaw = enIdx == null ? null : cell(row, enIdx);
                s.setSrcText(src);
                s.setEnText(en);
                s.setExistingKoText(blankToNull(koRaw));
                if (src == null) {
                    s.setSkipped(true);
                    s.setSkipReason(Segment.SkipReason.EMPTY_SRC);
                } else if (koIdx != null && EmptyMarkers.isMarker(koRaw) && enIdx != null && EmptyMarkers.isMarker(enRaw)) {
                    // 한국어·영어 칸에 둘 다 N/A 같은 표시가 적혀 있으면 게임에서 안 쓰는 행으로 보고 손대지 않는다.
                    s.setSkipped(true);
                    s.setSkipReason(Segment.SkipReason.INTENTIONAL);
                } else if (koIdx != null && !EmptyMarkers.isEmpty(koRaw) && !cfg.isRetranslateExisting()) {
                    kept.add(s);
                    s.setProofPending(cfg.isProofreadExisting());
                }
                created.add(s);
            }
        }
        segments.saveAll(created);
        List<TranslationRevision> seed = new ArrayList<>();
        for (Segment s : kept) seed.add(TranslationRevision.of(s.getId(), 0, s.getExistingKoText().trim(), TranslationRevision.Author.EXISTING));
        revisions.saveAll(seed);
        // 세그먼트와 0차 리비전이 실제 DB에 반영돼야 latest_ko 트리거가 돌고, 아래 이어받기 조회가 방금 넣은
        // 값(과 이 프로젝트 자신은 후보에서 빠진다는 사실)을 제대로 본다.
        segments.flush();
        inheritDecidedKo(projectId, p.getGame().getId(), cfg.getSheetName(), created);
        // 올린 파일에 이미 있던 한국어도 이 게임의 메모리가 된다. 사람이 고친 문장은 덮지 않는다.
        java.util.Map<String, String> memoryPairs = new java.util.LinkedHashMap<>();
        for (Segment s : created) if (s.getExistingKoText() != null && !EmptyMarkers.isEmpty(s.getExistingKoText()) && s.getSrcText() != null && !s.isSkipped())
            memoryPairs.putIfAbsent(s.getSrcText(), s.getExistingKoText().trim());
        memory.rememberAll(projectId, memoryPairs, MemoryEntry.Source.EXISTING, false);
        // 용어를 남겨 둔 경우(시트가 여럿), 방금 만든 이 시트의 행에도 확정 용어를 다시 붙인다.
        // 같은 트랜잭션에서 만든 세그먼트라 managed 상태이고, 용어가 없으면 아무것도 하지 않는다.
        termService.relinkConfirmed(projectId, cfg.getSheetName());
        // 검수 전용 파일은 용어·번역 단계를 건너뛰고 바로 검수로 간다.
        projects.bumpStep(projectId, p.isReviewOnly() ? 5 : 3);
        // 방금 설정한 시트를 보여 준다(미리보기는 그 시트만).
        return response(projectId, cfg.getSheetName(), 0, com.gametrans.config.Paging.DEFAULT_SIZE);
    }

    /**
     * 새로 정규화한 행 중 기존 한국어가 없는 행에, 같은 게임의 다른 파일이 이미 정한 번역(Fix)을 이어받게 한다.
     * 원문이 같은 다른 프로젝트의 행들이 최신 한국어를 하나로만 정했을 때만 이어받는다 — 서로 다르면 그대로
     * 비워 두고 DUP_CONFLICT 필터로 사람이 고르게 한다. author=MEMORY, revision 0(새 세그먼트라 아직
     * 리비전이 없다)으로 남긴다.
     * 이어받은 행은 그 자리에서 용어 불일치(term_miss)도 계산한다 — 안 하면 누가 "불일치 다시 계산"을 누를
     * 때까지 단어장과 어긋난 번역이 표시 없이 남는다. 단어장은 한 번만 읽는다.
     * created는 같은 트랜잭션에서 방금 저장한 managed 세그먼트다. term_miss를 벌크 update(updateTermMiss)로
     * 쓰면 이 managed 사본이 옛 값(null)으로 남아 같은 요청의 뒤쪽 읽기가 어긋나므로, 엔티티에 직접 넣어
     * flush 때 그 칸만(DynamicUpdate) 쓰이게 한다. 판정 대상 한국어는 방금 넣은 이어받은 문장이다 —
     * latest_ko는 트리거가 DB에서만 갱신하므로 managed 사본의 latestKo는 아직 비어 있다.
     */
    private void inheritDecidedKo(Long projectId, Long gameId, String sheetName, List<Segment> created) {
        List<SegmentRepository.InheritedKo> found = segments.findInheritableKo(gameId, projectId, sheetName);
        if (found.isEmpty()) return;
        for (SegmentRepository.InheritedKo row : found) revisions.add(row.getSegmentId(), row.getKo(), TranslationRevision.Author.MEMORY, null);
        log.info("같은 게임에서 {}행 이어받음", found.size());

        java.util.Map<String, String> glossary = termMiss.glossaryFor(projectId);
        if (glossary.isEmpty()) return;
        java.util.Map<Long, Segment> byId = new java.util.HashMap<>();
        for (Segment s : created) byId.put(s.getId(), s);
        for (SegmentRepository.InheritedKo row : found) {
            Segment s = byId.get(row.getSegmentId());
            if (s == null) continue; // 후보는 이 시트에서 방금 만든 행뿐이라 없을 수 없지만, 없으면 건너뛴다
            s.setTermMiss(GlossaryCheck.format(GlossaryCheck.missing(s.getSrcText(), row.getKo(), glossary)));
        }
    }

    /** 열 쌍 검증: 하나 이상, 원문 열 필수, 기존 열 모드면 한국어 열 필수, 같은 한국어 열을 두 쌍이 쓰면 안 된다. */
    static List<NormalizeConfig.ColumnPair> validatePairs(List<PairRequest> reqs) {
        if (reqs == null || reqs.isEmpty()) throw new IllegalArgumentException("원문 열을 하나 이상 지정하세요.");
        List<NormalizeConfig.ColumnPair> out = new ArrayList<>();
        java.util.Set<String> koTargets = new java.util.HashSet<>();
        for (PairRequest r : reqs) {
            if (r.srcCol() == null || r.srcCol().isBlank()) throw new IllegalArgumentException("간체 원문 열을 지정하세요.");
            NormalizeConfig.KoColMode mode = r.koColMode() == null ? NormalizeConfig.KoColMode.NEW : r.koColMode();
            // 기존 한국어를 읽는 모드: EXISTING(같은 열에 씀) · EXISTING_TO_NEW(새 열에 씀)
            boolean readsExisting = mode != NormalizeConfig.KoColMode.NEW;
            String ko = readsExisting ? upper(r.koCol()) : null;
            if (readsExisting && ko == null) throw new IllegalArgumentException("기존 한국어 열을 읽으려면 그 열을 지정해야 합니다.");
            String header = r.koHeader() == null || r.koHeader().isBlank() ? "KO" : r.koHeader().trim();
            // 쓰는 곳이 겹치는지 본다: 같은 열에 쓰면 그 열 문자, 새 열에 쓰면 새 열 이름
            String target = mode == NormalizeConfig.KoColMode.EXISTING ? ko : "NEW:" + header;
            if (!koTargets.add(target)) throw new IllegalArgumentException("두 열 쌍이 같은 한국어 열(" + target.replace("NEW:", "새 열 ") + ")에 쓰도록 되어 있습니다.");
            out.add(new NormalizeConfig.ColumnPair(upper(r.srcCol()), upper(r.enCol()), mode, ko,
                    mode == NormalizeConfig.KoColMode.EXISTING ? null : header));
        }
        return out;
    }

    private static String upper(String s) { return s == null || s.isBlank() ? null : s.trim().toUpperCase(); }

    /** 시트를 프로젝트에서 뺀다: 설정과 그 시트 세그먼트만 지운다. 용어는 프로젝트 전역이라 그대로 둔다. */
    @Transactional
    public void removeSheet(Long projectId, String sheetName) {
        NormalizeConfig cfg = configs.findByProjectIdAndSheetName(projectId, sheetName)
                .orElseThrow(() -> new com.gametrans.common.NotFoundException("설정하지 않은 시트입니다: " + sheetName));
        segments.deleteByProjectIdAndSheetName(projectId, sheetName);
        configs.delete(cfg);
    }

    /** 설정한 시트들(설정한 순서). 비어 있으면 아직 정규화하지 않은 프로젝트다. */
    public List<NormalizeConfig> configs(Long projectId) { return configs.findByProjectIdOrderByIdAsc(projectId); }

    public Optional<NormalizeConfig> getConfig(Long projectId, String sheetName) {
        return configs.findByProjectIdAndSheetName(projectId, sheetName);
    }

    public Optional<NormalizeResponse> current(Long projectId, String sheet, int page, int size) {
        if (configs.countByProjectId(projectId) == 0) return Optional.empty();
        // 설정하지 않은 시트를 물으면 빈 미리보기 대신 없다고 답한다.
        if (sheet != null && configs.findByProjectIdAndSheetName(projectId, sheet).isEmpty())
            throw new com.gametrans.common.NotFoundException("설정하지 않은 시트입니다: " + sheet);
        return Optional.of(response(projectId, sheet, page, size));
    }

    /** 프로젝트 전체 통계. */
    public NormalizeStats stats(Long projectId) { return stats(projectId, null); }

    /** sheet가 null이면 프로젝트 전체, 주면 그 시트만. */
    private NormalizeStats stats(Long projectId, String sheet) {
        SegmentRepository.Counts c = segments.counts(projectId, sheet);
        return new NormalizeStats(c.getTotalRows(), c.getNormalized(), c.getSkipped(), c.getWithEn(),
                segments.countKept(projectId, sheet), c.getIntentional());
    }

    private NormalizeResponse response(Long projectId, String sheet, int page, int size) {
        List<SheetConfigDto> sheets = configs.findByProjectIdOrderByIdAsc(projectId).stream()
                .map(c -> new SheetConfigDto(ConfigDto.of(c), stats(projectId, c.getSheetName()))).toList();
        List<Segment> rows = sheet == null
                ? segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(projectId)
                : segments.findByProjectIdAndSheetNameAndSkippedFalseOrderByRowNoAscPairIndexAsc(projectId, sheet);
        List<SegmentPreview> preview = com.gametrans.config.Paging.slice(rows, page, size).stream().map(SegmentPreview::of).toList();
        return new NormalizeResponse(sheets, stats(projectId), preview, page, size, rows.size());
    }

    private static String cell(WorkbookInspector.SheetRow row, int idx) {
        return idx < row.cells().size() ? row.cells().get(idx) : "";
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
    /** 원문 칸의 无·暂无·none은 보통 빈칸 표시지만, 한국어 칸에 진짜 번역(없음 등)이 있으면 게임에 나오는 문구다. */
    static String srcOrNull(String srcRaw, String koRaw) {
        if (EmptyMarkers.isMarker(srcRaw) && !EmptyMarkers.isEmpty(koRaw)) return srcRaw;
        return emptyToNull(srcRaw);
    }

    /** 빈칸과 N/A·None 같은 표시를 모두 null로. */
    private static String emptyToNull(String s) { return EmptyMarkers.isEmpty(s) ? null : s; }
}
