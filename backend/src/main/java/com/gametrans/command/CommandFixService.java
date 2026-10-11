package com.gametrans.command;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gametrans.activity.Activity;
import com.gametrans.activity.ActivityService;
import com.gametrans.llm.ToolLoop;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.review.BulkFixWriter;
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
    static final int MAX_COMMAND = 300;

    public record PlanRequest(String sheet, String command, List<OpSpec> ops, String check) {}
    public record Mismatch(List<String> src, List<String> ko) {}
    /** FIX면 after·by·warnings, CHECK면 mismatch(after는 null). */
    public record Row(Long segmentId, Integer rowNo, String src, String before, String after, List<String> by,
                      List<Pipeline.Warning> warnings, Mismatch mismatch) {}
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

    public CommandFixService(PipelineRunner runner, CommandAgent agent, SegmentRepository segments, BulkFixWriter writer,
                             ActivityService activities, ObjectMapper mapper) {
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
        List<SheetRow> rows = runner.rows(projectId, req.sheet());
        if (CHECK_TAG_MISMATCH.equals(req.check())) return check(null, List.of(), rows);
        Pipeline.Sidecar sidecar = runner.sidecar(runner.gameId(projectId));
        if (req.ops() != null) return fixPlan(null, Pipeline.compile(req.ops()), List.of(), rows, sidecar);
        String command = req.command() == null ? "" : req.command().strip();
        if (command.isEmpty()) throw new IllegalArgumentException("명령을 적어 주세요.");
        if (command.length() > MAX_COMMAND) throw new IllegalArgumentException("명령이 너무 깁니다.");
        CommandAgent.Outcome out;
        try {
            out = agent.run(command, rows, sidecar);
        } catch (org.springframework.web.client.RestClientException e) {
            throw new IllegalStateException("GPT에 연결하지 못했습니다. 잠시 뒤 다시 해 주세요.");
        }
        if (out.tagMismatch()) return check(out.summary(), out.trail(), rows);
        return fixPlan(out.summary(), Pipeline.compile(out.ops()), out.trail(), rows, sidecar);
    }

    private Plan fixPlan(String summary, Pipeline pipeline, List<ToolLoop.Trace> trail, List<SheetRow> rows, Pipeline.Sidecar sidecar) {
        List<Row> out = pipeline.run(rows, null, sidecar).stream()
                .map(c -> new Row(c.row().id(), c.row().rowNo(), c.row().src(), c.row().ko(), c.after(), c.by(), c.warnings(), null)).toList();
        return new Plan(summary, "FIX", pipeline.ops(), trail, out);
    }

    private Plan check(String summary, List<ToolLoop.Trace> trail, List<SheetRow> rows) {
        List<Row> out = runner.tagMismatches(rows).stream()
                .map(m -> new Row(m.row().id(), m.row().rowNo(), m.row().src(), m.row().ko(), null, List.of(), List.of(),
                        new Mismatch(m.srcOnly(), m.koOnly()))).toList();
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
        Pipeline pipeline = Pipeline.compile(req.ops());
        List<Item> items = req.items() == null ? List.of() : req.items().stream()
                .filter(it -> it != null && it.segmentId() != null && it.before() != null && it.after() != null).toList();
        if (items.isEmpty()) throw new IllegalArgumentException("고칠 행이 없습니다.");
        Map<Long, Item> byId = new LinkedHashMap<>();
        for (Item it : items) byId.putIfAbsent(it.segmentId(), it);
        List<SheetRow> rows = runner.rows(projectId, req.sheet());
        Map<Long, String> recomputed = new HashMap<>();
        for (Pipeline.Change c : pipeline.run(rows, Set.copyOf(byId.keySet()), runner.sidecar(runner.gameId(projectId)), false))
            recomputed.put(c.row().id(), c.after());
        List<Segment> editable = segments.findEditableInProject(projectId, byId.keySet());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("command", req.command() == null ? "" : req.command().strip());
        detail.put("ops", mapper.convertValue(pipeline.ops(), new TypeReference<List<Map<String, Object>>>() {}));
        return new FixResult(writer.write(editable, s -> {
            Item it = byId.get(s.getId());
            return it.before().equals(s.getLatestKo()) && it.after().equals(recomputed.get(s.getId())) ? it.after() : null;
        }, reviewerId, activities.lazyPerProject(Activity.Kind.COMMAND_FIX, reviewerId, detail)));
    }
}
