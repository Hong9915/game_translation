package com.gametrans.command;

import com.gametrans.workspace.SheetScope;
import com.gametrans.workspace.SheetScopes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gametrans.activity.Activity;
import com.gametrans.activity.ActivityService;
import com.gametrans.glossary.GlossaryCandidateService;
import com.gametrans.llm.ToolLoop;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.review.BulkFixWriter;
import com.gametrans.review.ReviewService;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 명령으로 고치기(설계 2026-10-08). 미리 보기는 세 모드다: 명령(GPT가 도구로 시험해 작업 목록을 낸다), ops(도구 버튼·저장 명령·손본
 * 작업 목록, GPT 없음), 검사(태그 짝). 저장은 같은 작업 목록을 다시 돌려 맞는 행만 한다. 확정 행은 보지도 고치지도 않는다.
 */
@Service
public class CommandFixService {
    public static final String CHECK_TAG_MISMATCH = "TAG_MISMATCH";
    /** 길이 기준을 넘는 행 목록(GPT 없음). 화면은 이 목록을 묶음으로 나눠 길이 줄이기(only)를 부른다. */
    public static final String CHECK_OVER_LENGTH = "OVER_LENGTH";
    static final int MAX_COMMAND = 300;

    /** only: 이 행들만 본다(길이 줄이기 묶음). ratio: 길이 기준 비율(없으면 기본값). */
    public record PlanRequest(String sheet, String command, List<OpSpec> ops, String check, List<Long> only, Double ratio) {}
    public record Mismatch(List<String> src, List<String> ko) {}
    /** FIX면 after·by·warnings, CHECK면 mismatch(after는 null). target은 길이 줄이기·길이 검사의 목표 글자 수(그 밖엔 null). */
    public record Row(Long segmentId, Integer rowNo, String src, String before, String after, List<String> by,
                      List<Pipeline.Warning> warnings, Mismatch mismatch, Long projectId, String sheet, Integer target) {
        Row(Long segmentId, Integer rowNo, String src, String before, String after, List<String> by,
            List<Pipeline.Warning> warnings, Mismatch mismatch, Long projectId, String sheet) {
            this(segmentId, rowNo, src, before, after, by, warnings, mismatch, projectId, sheet, null);
        }
    }
    /** kind = FIX | CHECK. summary는 GPT가 쓴 한 줄(GPT 없이면 null). */
    public record Plan(String summary, String kind, List<OpSpec> ops, List<ToolLoop.Trace> trail, List<Row> rows) {}
    public record Item(Long segmentId, String before, String after) {}
    public record FixRequest(String sheet, String command, List<OpSpec> ops, List<Item> items) {}
    public record FixResult(int applied) {}

    private final PipelineRunner runner;
    private final CommandAgent agent;
    private final SegmentRepository segments;
    private final BulkFixWriter writer;
    private final ActivityService activities;
    private final ObjectMapper mapper;
    private final SheetScopes scopes;
    private final BulkShorten shortener;
    private final ReviewService review;
    private final TermSwap termSwap;
    private final GlossaryCandidateService candidates;

    public CommandFixService(PipelineRunner runner, CommandAgent agent, SegmentRepository segments, BulkFixWriter writer,
                             ActivityService activities, ObjectMapper mapper, SheetScopes scopes, BulkShorten shortener, ReviewService review,
                             TermSwap termSwap, GlossaryCandidateService candidates) {
        this.candidates = candidates;
        this.termSwap = termSwap;
        this.scopes = scopes;
        this.shortener = shortener;
        this.review = review;
        this.runner = runner;
        this.agent = agent;
        this.segments = segments;
        this.writer = writer;
        this.activities = activities;
        this.mapper = mapper;
    }

    /** GPT 호출과 사이드카가 몇 초~수십 초 걸리므로 트랜잭션으로 묶지 않는다. */
    public Plan plan(Long projectId, PlanRequest req) {
        if (req.sheet() == null || req.sheet().isBlank()) throw new IllegalArgumentException("시트를 골라 주세요.");
        return plan(scopes.single(projectId, req.sheet()), req);
    }

    /** 범위 판(게임 화면): 범위 안 모든 행이 대상이다. req.sheet는 보지 않는다. */
    public Plan plan(SheetScope scope, PlanRequest req) {
        PipelineRunner.ScopeRows sr = runner.rows(scope);
        List<SheetRow> rows = sr.rows();
        if (CHECK_TAG_MISMATCH.equals(req.check())) return check(null, List.of(), rows, sr);
        double ratio = req.ratio() == null || req.ratio() <= 0 ? review.defaultRatio() : req.ratio();
        if (CHECK_OVER_LENGTH.equals(req.check())) return overLength(rows, ratio, sr);
        if (req.ops() != null) {
            Pipeline pipeline = Pipeline.compile(req.ops());
            if (pipeline.shorten()) return shortenPlan(pipeline, rows, req.only(), ratio, scope.gameId(), sr);
            if (pipeline.term() != null) return termPlan(null, pipeline, List.of(), rows, sr);
            return fixPlan(null, pipeline, List.of(), rows, runner.sidecar(scope.gameId()), sr);
        }
        Pipeline.Sidecar sidecar = runner.sidecar(scope.gameId());
        String command = req.command() == null ? "" : req.command().strip();
        if (command.isEmpty()) throw new IllegalArgumentException("명령을 적어 주세요.");
        if (command.length() > MAX_COMMAND) throw new IllegalArgumentException("명령이 너무 깁니다.");
        CommandAgent.Outcome out;
        try {
            out = agent.run(command, rows, sidecar);
        } catch (org.springframework.web.client.RestClientException e) {
            throw new IllegalStateException("GPT에 연결하지 못했습니다. 잠시 뒤 다시 해 주세요.");
        }
        if (out.tagMismatch()) return check(out.summary(), out.trail(), rows, sr);
        Pipeline pipeline = Pipeline.compile(out.ops());
        if (pipeline.term() != null) return termPlan(out.summary(), pipeline, out.trail(), rows, sr);
        return fixPlan(out.summary(), pipeline, out.trail(), rows, sidecar, sr);
    }

    /** 원래 번역에서 아무것도 지우지 않고 용어만 넣은 행. 화면이 체크를 꺼 두고 사람이 고른다. */
    static final Pipeline.Warning INSERTED = new Pipeline.Warning("INSERT", "끼워 넣음 — 원래 번역에서 바꾼 말이 없어요. 자리가 맞는지 보세요");

    /** 원문 용어 바꾸기: 대상 행을 GPT로 고친 미리 보기. 고치지 못한 행은 빠진다. */
    private Plan termPlan(String summary, Pipeline pipeline, List<ToolLoop.Trace> trail, List<SheetRow> rows, PipelineRunner.ScopeRows sr) {
        OpSpec op = pipeline.term();
        List<Row> out;
        try {
            out = termSwap.run(rows, op.src(), op.ko()).stream()
                    .map(res -> { SheetRow r = res.row(); SheetScope.SheetRef at = sr.refOf(r.id());
                        List<Pipeline.Warning> warn = TermSwap.insertOnly(r.ko(), res.after()) ? List.of(INSERTED) : List.of();
                        return new Row(r.id(), r.rowNo(), r.src(), r.ko(), res.after(), List.of(OpSpec.TERM), warn, null, at.projectId(), at.sheet()); })
                    .toList();
        } catch (org.springframework.web.client.RestClientException e) {
            throw new IllegalStateException("GPT에 연결하지 못했습니다. 잠시 뒤 다시 해 주세요.");
        }
        return new Plan(summary, "FIX", pipeline.ops(), trail, out);
    }

    private Plan fixPlan(String summary, Pipeline pipeline, List<ToolLoop.Trace> trail, List<SheetRow> rows, Pipeline.Sidecar sidecar,
                         PipelineRunner.ScopeRows sr) {
        List<Row> out = pipeline.run(rows, null, sidecar).stream()
                .map(c -> { SheetScope.SheetRef at = sr.refOf(c.row().id());
                    return new Row(c.row().id(), c.row().rowNo(), c.row().src(), c.row().ko(), c.after(), c.by(), c.warnings(), null, at.projectId(), at.sheet()); })
                .toList();
        return new Plan(summary, "FIX", pipeline.ops(), trail, out);
    }

    private Plan overLength(List<SheetRow> rows, double ratio, PipelineRunner.ScopeRows sr) {
        List<Row> out = rows.stream().filter(r -> BulkShorten.over(r, ratio))
                .map(r -> { SheetScope.SheetRef at = sr.refOf(r.id());
                    return new Row(r.id(), r.rowNo(), r.src(), r.ko(), null, List.of(), List.of(), null, at.projectId(), at.sheet(),
                            BulkShorten.target(r.src(), ratio)); })
                .toList();
        return new Plan(null, "CHECK", null, List.of(), out);
    }

    /** 길이 줄이기 묶음 하나. only가 꼭 있어야 한다 — 시트 전체를 한 요청에 GPT로 보내지 않는다. 줄이지 못한 행은 빠진다. */
    private Plan shortenPlan(Pipeline pipeline, List<SheetRow> rows, List<Long> only, double ratio, Long gameId, PipelineRunner.ScopeRows sr) {
        if (only == null || only.isEmpty()) throw new IllegalArgumentException("줄일 행을 골라 주세요.");
        Set<Long> ids = Set.copyOf(only);
        List<Row> out = new java.util.ArrayList<>();
        for (BulkShorten.Result res : shortener.run(rows.stream().filter(r -> ids.contains(r.id())).toList(), ratio, gameId)) {
            if (res.after() == null) continue;
            SheetRow r = res.row();
            SheetScope.SheetRef at = sr.refOf(r.id());
            List<Pipeline.Warning> warnings = com.gametrans.text.LengthCheck.count(res.after()) > res.target()
                    ? List.of(new Pipeline.Warning("LENGTH", "목표 " + res.target() + "자보다 깁니다")) : List.of();
            out.add(new Row(r.id(), r.rowNo(), r.src(), r.ko(), res.after(), List.of(OpSpec.SHORTEN), warnings, null, at.projectId(), at.sheet(), res.target()));
        }
        return new Plan(null, "FIX", pipeline.ops(), List.of(), out);
    }

    private Plan check(String summary, List<ToolLoop.Trace> trail, List<SheetRow> rows, PipelineRunner.ScopeRows sr) {
        List<Row> out = runner.tagMismatches(rows).stream()
                .map(m -> { SheetScope.SheetRef at = sr.refOf(m.row().id());
                    return new Row(m.row().id(), m.row().rowNo(), m.row().src(), m.row().ko(), null, List.of(), List.of(),
                            new Mismatch(m.srcOnly(), m.koOnly()), at.projectId(), at.sheet()); })
                .toList();
        return new Plan(summary, "CHECK", null, trail, out);
    }

    /**
     * 고른 행을 고친다. 같은 작업 목록을 지금 DB 행에 다시 돌려, before가 지금 번역과 같고 after가 다시 계산한 꼴과 같은 행만 쓴다.
     * 손으로 만든 after, 목록을 받은 뒤 바뀐 행, 확정·제외·다른 프로젝트 행은 건너뛴다. 바뀐 행은 변경 내역 "명령으로 고치기"
     * 한 건(명령·작업 목록 포함)으로 남는다.
     */
    @Transactional
    public FixResult fix(Long projectId, FixRequest req, Long reviewerId) {
        if (req.sheet() == null || req.sheet().isBlank()) throw new IllegalArgumentException("시트를 골라 주세요.");
        return fix(scopes.single(projectId, req.sheet()), req, reviewerId);
    }

    /** 범위 판(게임 화면). 범위 밖 행은 건너뛴다. 바뀐 행은 파일마다 변경 내역 한 건. */
    @Transactional
    public FixResult fix(SheetScope scope, FixRequest req, Long reviewerId) {
        Pipeline pipeline = Pipeline.compile(req.ops());
        List<Item> items = req.items() == null ? List.of() : req.items().stream()
                .filter(it -> it != null && it.segmentId() != null && it.before() != null && it.after() != null).toList();
        if (items.isEmpty()) throw new IllegalArgumentException("고칠 행이 없습니다.");
        Map<Long, Item> byId = new LinkedHashMap<>();
        for (Item it : items) byId.putIfAbsent(it.segmentId(), it);
        List<SheetRow> rows = runner.rows(scope).rows();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("command", req.command() == null ? "" : req.command().strip());
        detail.put("ops", mapper.convertValue(pipeline.ops(), new TypeReference<List<Map<String, Object>>>() {}));
        OpSpec term = pipeline.term();
        if (term != null) {
            // 용어 바꾸기도 GPT 결과라 다시 계산할 수 없다 — 범위 안 행이고, 원문에 용어가 들고, 그 사이 번역이 그대로이며, 바꿀 한국어가 든 행만 쓴다
            Map<Long, SheetRow> inScope = new HashMap<>();
            for (SheetRow r : rows) inScope.put(r.id(), r);
            List<Segment> editable = segments.findEditableInProjects(scope.projectIds(), byId.keySet());
            int n = writer.write(editable, s -> {
                Item it = byId.get(s.getId());
                SheetRow r = inScope.get(s.getId());
                return r != null && r.src().contains(term.src()) && it.before().equals(s.getLatestKo())
                        && TermSwap.acceptable(it.before(), it.after(), term.ko()) ? it.after().trim() : null;
            }, reviewerId, activities.lazyPerProject(Activity.Kind.COMMAND_FIX, reviewerId, detail));
            // 저장된 행이 있으면 단어장 후보로 남긴다(승인 대기). 명령 글이 비었으면 이유는 없다.
            String cmd = req.command() == null || req.command().isBlank() ? null : req.command().strip();
            candidates.record(scope.gameId(), term.src(), term.ko(), null, n, cmd, "COMMAND", null, reviewerId);
            return new FixResult(n);
        }
        if (pipeline.shorten()) {
            // GPT 결과라 다시 계산할 수 없다 — 범위 안 행이고, 그 사이 번역이 그대로이며, 짧아졌고 태그 짝이 맞는 행만 쓴다
            Set<Long> inScope = new java.util.HashSet<>();
            for (SheetRow r : rows) inScope.add(r.id());
            List<Segment> editable = segments.findEditableInProjects(scope.projectIds(), byId.keySet());
            return new FixResult(writer.write(editable, s -> {
                Item it = byId.get(s.getId());
                return inScope.contains(s.getId()) && it.before().equals(s.getLatestKo()) && BulkShorten.acceptable(it.before(), it.after())
                        ? it.after().trim() : null;
            }, reviewerId, activities.lazyPerProject(Activity.Kind.COMMAND_FIX, reviewerId, detail)));
        }
        Map<Long, String> recomputed = new HashMap<>();
        for (Pipeline.Change c : pipeline.run(rows, Set.copyOf(byId.keySet()), runner.sidecar(scope.gameId()), false))
            recomputed.put(c.row().id(), c.after());
        // 다시 계산한 꼴(recomputed)은 범위 행에서만 나오므로 범위 밖 행은 아래 비교에서 걸러진다
        List<Segment> editable = segments.findEditableInProjects(scope.projectIds(), byId.keySet());
        return new FixResult(writer.write(editable, s -> {
            Item it = byId.get(s.getId());
            return it.before().equals(s.getLatestKo()) && it.after().equals(recomputed.get(s.getId())) ? it.after() : null;
        }, reviewerId, activities.lazyPerProject(Activity.Kind.COMMAND_FIX, reviewerId, detail)));
    }
}
