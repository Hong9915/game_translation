package com.gametrans.connector;

import com.gametrans.connector.ConnectorDtos.ColumnStat;
import com.gametrans.connector.ConnectorDtos.FileDto;
import com.gametrans.connector.ConnectorDtos.InspectResponse;
import com.gametrans.connector.ConnectorDtos.InspectSheet;
import com.gametrans.connector.ConnectorDtos.NormalizeSheetsRequest;
import com.gametrans.connector.ConnectorDtos.NormalizeSheetsResponse;
import com.gametrans.connector.ConnectorDtos.PlanPair;
import com.gametrans.connector.ConnectorDtos.SheetPlan;
import com.gametrans.connector.ConnectorDtos.SheetResult;
import com.gametrans.normalize.NormalizeConfig;
import com.gametrans.normalize.NormalizeConfigRepository;
import com.gametrans.normalize.NormalizeDtos.ConfigDto;
import com.gametrans.normalize.NormalizeDtos.NormalizeRequest;
import com.gametrans.normalize.NormalizeDtos.NormalizeStats;
import com.gametrans.normalize.NormalizeDtos.PairRequest;
import com.gametrans.normalize.NormalizeService;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.normalize.SheetHasWorkException;
import com.gametrans.project.Project;
import com.gametrans.project.ProjectRepository;
import com.gametrans.project.ProjectService;
import com.gametrans.reviewer.ReviewerService;
import com.gametrans.text.EmptyMarkers;
import com.gametrans.text.Simplifier;
import com.gametrans.xlsx.ColumnGuesser;
import com.gametrans.xlsx.WorkbookInspector;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Service;

/** 커넥터의 정규화 쪽 API: 게임의 파일 목록, 파일의 시트 들여다보기, 여러 시트 한꺼번에 정규화. */
@Service
public class ConnectorNormalizeService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ConnectorNormalizeService.class);
    static final int DEFAULT_LIMIT = 30;
    static final int MAX_LIMIT = 50;
    static final int MAX_SHEETS = 40;
    /**
     * 이 시간이 지나면 남은 시트는 돌리지 않고 NOT_RUN으로 돌려준다(요청 하나가 너무 오래 걸려 끊기지 않게).
     * 가장 긴 호출은 이 시간 + 시트 하나이고, 프로드는 측정한 맥보다 느려서 30초로 둔다.
     */
    static final long TIME_BUDGET_MS = 30_000;
    static final String HAS_WORK = "번역·검수한 내용이 있어 다시 정규화할 수 없습니다.";

    private final ProjectService projects;
    private final ProjectRepository projectRepo;
    private final NormalizeConfigRepository configs;
    private final SegmentRepository segments;
    private final Simplifier simplifier;
    private final NormalizeService normalizer;
    private final ReviewerService reviewers;
    /** 시험에서 시간을 바꿔 끼우려고 뺐다. */
    LongSupplier clock = System::currentTimeMillis;
    /**
     * 커넥터에서 워크북을 여는 일(inspect·normalize)은 한 번에 하나만. 모델은 읽기 도구를 나란히 부르고, 여는 것마다
     * 워크북 전체를 메모리에 올린다(힙 2GB). 공정하게(온 순서대로) 기다린다. files는 워크북을 열지 않아 빠진다.
     */
    final Semaphore workbookGate = new Semaphore(1, true);

    public ConnectorNormalizeService(ProjectService projects, ProjectRepository projectRepo, NormalizeConfigRepository configs,
                                     SegmentRepository segments, Simplifier simplifier, NormalizeService normalizer, ReviewerService reviewers) {
        this.projects = projects;
        this.projectRepo = projectRepo;
        this.configs = configs;
        this.segments = segments;
        this.simplifier = simplifier;
        this.normalizer = normalizer;
        this.reviewers = reviewers;
    }

    /** 올린 순서(업로드 시각, 같으면 id). 워크북은 열지 않는다. */
    public List<FileDto> files(Long gameId) {
        List<Project> ps = new ArrayList<>(projectRepo.findByGameIdOrderByUploadedAtDesc(gameId));
        ps.sort(Comparator.comparing(Project::getUploadedAt).thenComparing(Project::getId));
        Map<Long, Long> configured = configs.findByProjectIdIn(ps.stream().map(Project::getId).toList()).stream()
                .collect(Collectors.groupingBy(NormalizeConfig::getProjectId, Collectors.counting()));
        List<FileDto> out = new ArrayList<>();
        for (Project p : ps) out.add(new FileDto(p.getId(), p.getFileName(), p.isReviewOnly(), configured.getOrDefault(p.getId(), 0L).intValue()));
        return out;
    }

    /** 워크북을 한 번만 열어 offset부터 limit장의 시트를 들여다본다. CSV는 올릴 때 이미 시트 하나짜리 xlsx로 저장돼 있다. */
    public InspectResponse inspect(Long projectId, Integer offset, Integer limit) {
        Project p = projects.get(projectId);
        int from = Math.max(0, offset == null ? 0 : offset);
        int n = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        acquireWorkbookGate();
        try {
            return inspectOpen(p, projectId, from, n);
        } finally {
            workbookGate.release();
        }
    }

    private InspectResponse inspectOpen(Project p, Long projectId, int from, int n) {
        Map<String, NormalizeConfig> byName = new HashMap<>();
        for (NormalizeConfig c : configs.findByProjectIdIn(List.of(projectId))) byName.put(c.getSheetName(), c);
        try (Workbook wb = WorkbookInspector.open(projects.originalBytes(p))) {
            int total = wb.getNumberOfSheets();
            List<InspectSheet> out = new ArrayList<>();
            for (int i = from; i < Math.min(total, from + n); i++) {
                Sheet s = wb.getSheetAt(i);
                NormalizeConfig cfg = byName.get(s.getSheetName());
                out.add(new InspectSheet(s.getSheetName(), i, s.getPhysicalNumberOfRows(), SheetProbe.top(s), SheetProbe.columns(s, simplifier),
                        ColumnGuesser.guess(WorkbookInspector.headerOf(s, 1)),
                        cfg == null ? null : ConfigDto.of(cfg),
                        // 설정이 없어도 행이 남은 시트는 이미 정규화한 것으로 본다(canReplace가 null이 아님)
                        cfg == null && !segments.existsByProjectIdAndSheetName(projectId, s.getSheetName()) ? null : !segments.hasWork(projectId, s.getSheetName())));
            }
            return new InspectResponse(projectId, p.getFileName(), total, from, out);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /**
     * 워크북을 한 번 열어 요청한 시트를 차례로 정규화한다. 이 메서드에는 트랜잭션이 없다 — 시트 하나가
     * normalizeSheet의 트랜잭션 하나이고, 한 시트가 실패해도 앞서 끝난 시트는 그대로 남는다.
     * 정규화는 그 시트의 행(번역·검수·내역)을 지우고 다시 만들기 때문에, 지우기 전에 거절 규칙을 모두 본다.
     */
    public NormalizeSheetsResponse normalize(Long projectId, NormalizeSheetsRequest req, Long reviewerId) {
        reviewers.requireForConnector(reviewerId);
        int n = req == null || req.sheets() == null ? 0 : req.sheets().size();
        if (n < 1 || n > MAX_SHEETS) throw new IllegalArgumentException("한 번에 1~" + MAX_SHEETS + "개 시트를 정규화할 수 있습니다.");
        for (SheetPlan sp : req.sheets()) {
            if (sp == null || sp.sheetName() == null || sp.sheetName().isBlank() || sp.pairs() == null || sp.pairs().isEmpty())
                throw new IllegalArgumentException("시트 정보가 비어 있습니다.");
            // 요청 모양이 틀린 것이라 위와 같이 요청 전체를 400으로(웹 시트 목록과 같은 문구)
            if (sp.headerRow() != null && sp.headerRow() < 0) throw new IllegalArgumentException("헤더 행은 0(없음) 이상이어야 합니다.");
        }
        Project p = projects.get(projectId);
        boolean replace = Boolean.TRUE.equals(req.replace());
        // 기다린 시간도 요청 시간이라 문 앞에서 잰다 — 오래 기다렸으면 한 시트만 하고 나머지는 NOT_RUN
        long start = clock.getAsLong();
        List<SheetResult> out = new ArrayList<>();
        acquireWorkbookGate();
        try (Workbook wb = WorkbookInspector.open(projects.originalBytes(p))) {
            for (SheetPlan plan : req.sheets()) {
                if (!out.isEmpty() && clock.getAsLong() - start >= TIME_BUDGET_MS) {
                    out.add(result(plan, "NOT_RUN", "시간이 다 돼 하지 않았습니다. 이 시트부터 다시 부르세요."));
                    continue;
                }
                out.add(runSheet(p, wb, plan, replace));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            workbookGate.release();
        }
        // 웹의 NormalizeService.normalize와 같다: 하나라도 됐으면 검수 전용 파일은 검수(5), 아니면 번역(3) 단계로.
        if (out.stream().anyMatch(r -> r.status().equals("OK"))) projects.bumpStep(projectId, p.isReviewOnly() ? 5 : 3);
        return new NormalizeSheetsResponse(out);
    }

    private SheetResult runSheet(Project p, Workbook wb, SheetPlan plan, boolean replace) {
        try {
            Sheet sheet = plan.sheetName() == null ? null : wb.getSheet(plan.sheetName());
            if (sheet == null) return result(plan, "FAILED", "시트를 찾을 수 없습니다: " + plan.sheetName());
            List<PlanPair> pairs = plan.pairs() == null ? List.of() : plan.pairs().stream().filter(Objects::nonNull)
                    .map(x -> new PlanPair(up(x.srcCol()), up(x.enCol()), up(x.koCol()))).toList();
            String keyCol = up(plan.keyCol());
            String badCol = badColumn(keyCol, pairs);
            if (badCol != null) return result(plan, "FAILED", "열 문자가 아닙니다: " + badCol);
            String refusal = refusal(p.getId(), sheet, pairs, replace);
            if (refusal != null) return result(plan, "REFUSED", refusal);

            List<PairRequest> reqPairs = new ArrayList<>();
            for (int i = 0; i < pairs.size(); i++) {
                PlanPair x = pairs.get(i);
                reqPairs.add(new PairRequest(x.srcCol(), x.enCol(), x.koCol() == null ? NormalizeConfig.KoColMode.NEW : NormalizeConfig.KoColMode.EXISTING_TO_NEW,
                        x.koCol(), i == 0 ? "KR_Fix" : "KR_Fix" + (i + 1), null));
            }
            int headerRow = plan.headerRow() == null ? 1 : plan.headerRow();
            NormalizeStats stats;
            try {
                // true: 트랜잭션 안에서 지우기 전에 행을 잠그고 검수 흔적을 다시 본다(위 refusal과 이 사이의 경합)
                stats = normalizer.normalizeSheet(p, wb,
                        new NormalizeRequest(sheet.getSheetName(), headerRow, keyCol, reqPairs, false, false, plan.srcToSimplified()), true);
            } catch (SheetHasWorkException e) {
                return result(plan, "REFUSED", HAS_WORK);
            } catch (IllegalArgumentException e) {
                return result(plan, "FAILED", e.getMessage()); // 예상한 입력 오류: 메시지 그대로
            }
            // 여기부터는 시트가 이미 커밋됐다 — 경고를 못 만들어도 OK로 돌려준다.
            List<String> warns = List.of();
            try {
                boolean simplified = configs.findByProjectIdAndSheetName(p.getId(), sheet.getSheetName()).map(NormalizeConfig::isSrcToSimplified).orElse(false);
                warns = warnings(sheet, pairs, keyCol, headerRow, stats, simplified);
            } catch (RuntimeException e) {
                log.error("경고를 만들지 못했습니다: {}", plan.sheetName(), e);
            }
            return new SheetResult(plan.sheetName(), "OK", null, stats, warns);
        } catch (RuntimeException e) {
            log.error("시트 정규화 중 예기치 못한 오류: {}", plan.sheetName(), e);
            return result(plan, "FAILED", "정규화 중 오류가 났습니다.");
        }
    }

    /** 거절 규칙 1~4(먼저 맞는 것). 아무것도 지우기 전에 본다. 거절이 아니면 null. */
    private String refusal(Long projectId, Sheet sheet, List<PlanPair> pairs, boolean replace) {
        // 설정 없이 행만 남은 시트도 정규화한 시트다 — 다시 만들면 그 행(검수 포함)이 지워진다.
        if (configs.findByProjectIdAndSheetName(projectId, sheet.getSheetName()).isPresent()
                || segments.existsByProjectIdAndSheetName(projectId, sheet.getSheetName())) {
            if (!replace) return "이미 정규화한 시트입니다.";
            if (segments.hasWork(projectId, sheet.getSheetName())) return HAS_WORK;
        }
        for (PlanPair x : pairs) {
            if (x.koCol() != null && pairs.stream().anyMatch(o -> x.koCol().equals(o.srcCol()))) return "원문 열과 한국어 열이 같습니다(" + x.koCol() + ").";
        }
        for (PlanPair x : pairs) {
            if (x.koCol() == null) continue;
            int idx = CellReference.convertColStringToIndex(x.koCol());
            ColumnStat st = SheetProbe.column(sheet, idx, simplifier);
            if (SheetProbe.filledCount(sheet, idx) >= 5 && st.hangul() < 10) return "한국어 열(" + x.koCol() + ")에 한글이 거의 없습니다(" + st.hangul() + "%).";
        }
        return null;
    }

    /** 막지는 않지만 열 선택이 틀렸을 수 있다는 신호. 순서는 정해져 있다. */
    private List<String> warnings(Sheet sheet, List<PlanPair> pairs, String keyCol, int headerRow, NormalizeStats stats, boolean simplified) {
        List<String> out = new ArrayList<>();
        List<ColumnStat> src = pairs.stream().map(x -> SheetProbe.column(sheet, CellReference.convertColStringToIndex(x.srcCol()), simplifier)).toList();
        for (ColumnStat st : src) if (st.han() < 50) out.add("원문 열(" + st.letter() + ")에 한자가 적습니다(" + st.han() + "%).");
        if (stats.normalized() == 0) out.add("정규화된 행이 없습니다.");
        if (stats.skipped() * 2 > stats.totalRows()) out.add("건너뛴 행이 절반을 넘습니다(" + stats.skipped() + "/" + stats.totalRows() + ").");
        if (!simplified) for (ColumnStat st : src) if (st.traditional() > 0) out.add("원문에 번체가 섞였는데 간체 변환이 꺼져 있습니다(" + st.traditional() + "%).");
        fallbackWarning(sheet, pairs, keyCol, headerRow).ifPresent(out::add);
        return out;
    }

    /** 원문이 빈 행의 다른 열(역할 없는 열)에 한자 글이 있으면, 그런 행이 가장 많은 열(같으면 왼쪽). 시트 전체 행을 본다. */
    private Optional<String> fallbackWarning(Sheet sheet, List<PlanPair> pairs, String keyCol, int headerRow) {
        Set<String> roles = new HashSet<>();
        if (keyCol != null) roles.add(keyCol);
        for (PlanPair x : pairs) {
            roles.add(x.srcCol());
            if (x.enCol() != null) roles.add(x.enCol());
            if (x.koCol() != null) roles.add(x.koCol());
        }
        int[] srcIdx = pairs.stream().mapToInt(x -> CellReference.convertColStringToIndex(x.srcCol())).toArray();
        Map<Integer, Integer> counts = new HashMap<>();
        for (int r = headerRow; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null || Arrays.stream(srcIdx).noneMatch(i -> EmptyMarkers.isEmpty(SheetProbe.text(row.getCell(i))))) continue;
            for (int c = 0; c < row.getLastCellNum(); c++) {
                if (roles.contains(CellReference.convertNumToColString(c)) || !hasHan(SheetProbe.text(row.getCell(c)))) continue;
                counts.merge(c, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .max(Map.Entry.<Integer, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(e -> "원문이 빈 " + e.getValue() + "행은 " + CellReference.convertNumToColString(e.getKey()) + "열에 글이 있습니다.");
    }

    /** 기다리는 중에 스레드가 끊기면(요청 취소·종료) 끊김 표시를 되살리고 그만둔다. */
    private void acquireWorkbookGate() {
        try {
            workbookGate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("워크북을 열려고 기다리다 중단됐습니다.", e);
        }
    }

    private static final java.util.regex.Pattern COLUMN_LETTERS = java.util.regex.Pattern.compile("^[A-Z]{1,3}$");

    /** 대문자로 바꾼 열 값 중 열 문자(A~ZZZ)가 아닌 첫 값. 비운 값(null)은 넘어간다. 모두 맞으면 null. */
    private static String badColumn(String keyCol, List<PlanPair> pairs) {
        List<String> cols = new ArrayList<>();
        cols.add(keyCol);
        for (PlanPair x : pairs) {
            cols.add(x.srcCol());
            cols.add(x.enCol());
            cols.add(x.koCol());
        }
        for (String c : cols) if (c != null && !COLUMN_LETTERS.matcher(c).matches()) return c;
        return null;
    }

    private static boolean hasHan(String t) { return t.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN); }

    private static SheetResult result(SheetPlan plan, String status, String reason) { return new SheetResult(plan.sheetName(), status, reason, null, List.of()); }

    private static String up(String s) { return s == null || s.isBlank() ? null : s.trim().toUpperCase(); }
}
