package com.gametrans.glossary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gametrans.glossary.GlossaryDtos.Rendering;
import com.gametrans.glossary.GlossarySuggester.Row;
import com.gametrans.llm.LlmClient;
import com.gametrans.llm.LlmClient.ToolSpec;
import com.gametrans.llm.Prompts;
import com.gametrans.llm.ToolLoop;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 단어장 요청 GPT 표기 확인(설계 2026-11-02 §4). 용어 하나를 두고 GPT가 find_rows로 실제 행을 열어 본 뒤, finish로 그 용어를
 * 옮긴 한국어 꼴만 낸다. 통계 판정(GlossaryVariants)은 같은 행에 같이 나온 낱말(버프 등)을 다른 표기로 잡아서 이걸 더했다.
 * <pre>
 *   GPT → find_rows {contains:"公会战"}                 서버: 원문에 든 겹치지 않는 쌍(되풀이 수)과 총 행 수
 *   GPT → finish {renderings:[{ko:"부족전"}], reason}   서버: 표기마다 실제 행에 있는지 검사, 행 수·예문은 직접 센다
 * </pre>
 * 번역본에 없는 표기는 finishCheck가 돌려보낸다. 실패·미완료·예외면 null — 부르는 쪽이 통계 결과를 그대로 둔다.
 */
@Component
public class TermFinder {
    private static final Logger log = LoggerFactory.getLogger(TermFinder.class);
    public static final String FIND_ROWS = "find_rows", FINISH = "finish";
    static final int MAX_CALLS = 4;
    static final int SAMPLES = 12;
    static final int EXAMPLES = 8;
    static final int CHARS = 120;

    /** renderings: 행 수 많은 순(첫째 = 주 표기). none이면 renderings는 빈 목록. reason은 GPT가 남긴 까닭 한 줄(없으면 null). */
    public record Found(List<Rendering> renderings, boolean none, String reason) {}

    private final LlmClient llm;
    private final ObjectMapper mapper;

    public TermFinder(LlmClient llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    /** 실패·미완료·예외면 null(호출한 쪽이 통계 결과를 그대로 둔다). */
    public Found find(String src, String category, GlossaryVariants.Pairs pairs) {
        List<Integer> hits = indexes(pairs, src, true);
        int total = hits.stream().mapToInt(i -> pairs.weight()[i]).sum();
        List<String> samples = hits.stream().limit(SAMPLES).map(i -> line(pairs, i)).toList();
        try {
            ToolLoop.Result r = ToolLoop.run(llm, mapper, "TERM_FIND", Prompts.TERM_FIND_SYSTEM,
                    Prompts.termFindUser(src, category, samples, total), tools(pairs), FINISH, null, MAX_CALLS,
                    a -> finishProblem(pairs, src, a));
            if (r.finish() == null) return null;
            JsonNode f = r.finish();
            String reason = text(f.path("reason"));
            if (f.path("none").asBoolean(false)) return new Found(List.of(), true, reason);
            List<String> kos = kos(f);
            if (reason == null)
                for (JsonNode n : f.path("renderings")) if ((reason = text(n.path("reason"))) != null) break;
            return new Found(count(pairs, src, kos), false, reason);
        } catch (RuntimeException e) {
            log.warn("단어장 요청 GPT 표기 확인 실패({}): {}", src, e.getMessage());
            return null;
        }
    }

    /** finish 인자가 틀리면 그 까닭(모델에 돌려줘 고치게 한다), 맞으면 null. */
    private static String finishProblem(GlossaryVariants.Pairs pairs, String src, JsonNode f) {
        List<Integer> hits = indexes(pairs, src, true);
        if (f.path("none").asBoolean(false)) {
            // none이면 통계 표기를 지우고 다시 확인도 안 하므로, 행이 있는데 까닭 없이 none이면 돌려보낸다(검토 2026-11-02)
            if (hits.isEmpty() || text(f.path("reason")) != null) return null;
            int rows = hits.stream().mapToInt(i -> pairs.weight()[i]).sum();
            return "원문에 이 용어가 든 행이 " + rows + "개 있습니다. find_rows로 확인하고, 정말 번역본에 없으면 reason과 함께 none: true";
        }
        List<String> kos = kos(f);
        if (kos.isEmpty()) return "renderings가 비었습니다. 번역본에 없으면 none: true";
        for (String ko : kos)
            if (hits.stream().noneMatch(i -> pairs.rows().get(i).ko().contains(ko))) return "이 표기는 번역본에 없습니다: " + ko;
        return null;
    }

    /** 다른 표기 뒤에 붙으면 조사로 보는 꼬리. 부족전은 → 부족전처럼 조사 붙은 꼴은 따로 세지 않는다(GlossaryVariants와 같은 뜻). */
    private static final List<String> PARTICLES = List.of("에서", "에게", "으로", "부터", "까지", "이나", "은", "는", "이", "가",
            "을", "를", "의", "에", "로", "와", "과", "도", "만", "나");

    /**
     * finish의 표기들 — 앞뒤 공백을 다듬고 빈 것·중복은 뺀다. 다른 표기에 조사만 붙은 꼴도 뺀다(승인 때 바꿀 표기가 되면 조사가 날아간다).
     * 순서는 GPT가 낸 대로.
     */
    private static List<String> kos(JsonNode f) {
        Set<String> all = new LinkedHashSet<>();
        for (JsonNode n : f.path("renderings")) {
            String ko = text(n.path("ko"));
            if (ko != null) all.add(ko);
        }
        List<String> out = new ArrayList<>();
        for (String ko : all)
            if (all.stream().noneMatch(p -> !p.equals(ko) && ko.startsWith(p) && PARTICLES.contains(ko.substring(p.length())))) out.add(ko);
        return out;
    }

    /** 표기마다 원문에 src가 들고 한국어에 그 표기가 든 쌍의 되풀이 합과 첫 예문. 행 수 내림차순(같으면 GPT 순서). */
    private static List<Rendering> count(GlossaryVariants.Pairs pairs, String src, List<String> kos) {
        List<Integer> hits = indexes(pairs, src, true);
        List<Rendering> out = new ArrayList<>();
        for (String ko : kos) {
            int rows = 0;
            Row first = null;
            for (int i : hits) {
                Row r = pairs.rows().get(i);
                if (!r.ko().contains(ko)) continue;
                rows += pairs.weight()[i];
                if (first == null) first = r;
            }
            if (first != null) out.add(new Rendering(ko, rows, GlossaryRecommender.clip(first.src()), GlossaryRecommender.clip(first.ko())));
        }
        out.sort(Comparator.comparingInt(Rendering::rows).reversed());   // 안정 정렬 — 같은 행 수면 GPT 순서
        return out;
    }

    /** 원문(inSrc) 또는 한국어에 text가 든 쌍의 번호. */
    private static List<Integer> indexes(GlossaryVariants.Pairs pairs, String text, boolean inSrc) {
        List<Integer> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (int i = 0; i < pairs.rows().size(); i++) {
            Row r = pairs.rows().get(i);
            String s = inSrc ? r.src() : r.ko();
            if (s != null && s.contains(text)) out.add(i);
        }
        return out;
    }

    private List<ToolLoop.Tool> tools(GlossaryVariants.Pairs pairs) {
        Map<String, Object> rendering = Map.of("type", "object", "properties", Map.of(
                "ko", Map.of("type", "string"), "reason", Map.of("type", "string")), "required", List.of("ko"));
        return List.of(
                tool(FIND_ROWS, "Rows of this game whose source (in=src, default) or Korean (in=ko) contains the text. "
                                + "Returns the total row count and up to limit distinct (src, ko) pairs with how many times each repeats.",
                        Map.of("type", "object", "properties", Map.of("contains", Map.of("type", "string"),
                                "in", Map.of("type", "string", "enum", List.of("src", "ko")), "limit", Map.of("type", "integer", "maximum", EXAMPLES)),
                                "required", List.of("contains")),
                        a -> findRows(pairs, a)),
                tool(FINISH, "Finish: the Korean renderings of the term, most used first, or none: true if it is not translated anywhere. "
                                + "reason: one short Korean sentence, optional.",
                        Map.of("type", "object", "properties", Map.of("renderings", Map.of("type", "array", "items", rendering),
                                "none", Map.of("type", "boolean"), "reason", Map.of("type", "string"))),
                        a -> "ok"));
    }

    private String findRows(GlossaryVariants.Pairs pairs, JsonNode a) {
        String text = a.path("contains").asText("");
        boolean inSrc = !"ko".equals(a.path("in").asText("src"));
        int limit = Math.max(1, Math.min(EXAMPLES, a.path("limit").asInt(EXAMPLES)));
        List<Integer> hits = indexes(pairs, text, inSrc);
        int count = hits.stream().mapToInt(i -> pairs.weight()[i]).sum();
        List<Map<String, Object>> shown = hits.stream().limit(limit).map(i -> Map.<String, Object>of(
                "src", cut(pairs.rows().get(i).src()), "ko", cut(pairs.rows().get(i).ko()), "times", pairs.weight()[i])).toList();
        return json(Map.of("count", count, "pairs", shown));
    }

    private static String line(GlossaryVariants.Pairs pairs, int i) {
        Row r = pairs.rows().get(i);
        return cut(r.src()) + " ⇒ " + cut(r.ko()) + " (×" + pairs.weight()[i] + ")";
    }

    private ToolLoop.Tool tool(String name, String description, Map<String, Object> params, Function<JsonNode, String> f) {
        ToolSpec spec = new ToolSpec(name, description, params);
        return new ToolLoop.Tool() {
            @Override public ToolSpec spec() { return spec; }
            @Override public String call(JsonNode args) { return f.apply(args); }
        };
    }

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText("").strip();
        return s.isEmpty() ? null : s;
    }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private static String cut(String s) {
        if (s == null) return "";
        String one = s.replace("\n", "⏎");
        return one.length() <= CHARS ? one : one.substring(0, CHARS) + "…";
    }
}
