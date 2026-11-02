package com.gametrans.command;

import com.gametrans.llm.LlmClient;
import com.gametrans.llm.LlmClient.FixTermsRow;
import com.gametrans.llm.LlmClient.TranslationResult;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.text.EmptyMarkers;
import com.gametrans.text.PlaceholderCheck;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 명령으로 고치기의 원문 용어 바꾸기(2026-10-26). "关卡 이거를 스테이지로" — 원문에 关卡이 든 행의 지금 번역이 레벨·단계·Level·빠짐으로
 * 제각각이라 찾아 바꾸기 규칙 하나로 못 잡는다. 그래서 행마다 GPT가 그 자리를 찾아 고쳐 쓴다.
 *
 * <p>숫자만 다른 행(MissionRepository의 "关卡 54-14 / Level 54·14" 2천여 행)은 원문·번역의 숫자를 #으로 접은 모양이 같으면 한 묶음으로 보고,
 * 묶음 대표 한 행만 GPT에 묻는다. 대표의 번역과 고친 글에 든 숫자가 같은 차례면 고친 글의 숫자 자리에 다른 행의 숫자를 넣어 나머지를 만든다.
 * 그렇게 못 만드는 묶음(GPT가 숫자를 바꾸거나 옮김)은 행마다 따로 묻는다. GPT 결과라 저장 때 다시 계산하지 않고 acceptable로 확인만 한다.
 */
@Service
public class TermSwap {
    private static final Logger log = LoggerFactory.getLogger(TermSwap.class);
    /** GPT에 묻는 행 수의 한도(대표 + 따로 묻는 행). 넘으면 범위를 좁히라고 한다. */
    static final int MAX_ASKED = 300;
    static final int CHUNK = 15;
    static final int THREADS = 4;
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    public record Result(SheetRow row, String after) {}

    private final SegmentRepository segments;
    private final LlmClient llm;

    public TermSwap(SegmentRepository segments, LlmClient llm) {
        this.segments = segments;
        this.llm = llm;
    }

    /** 바꿀 대상: 확정이 아니고 번역이 있으며, 원문에 용어가 들고 번역에 바꿀 한국어가 아직 없는 행. */
    public static boolean target(SheetRow r, String src, String ko) {
        return !r.confirmed() && r.src() != null && r.src().contains(src) && !EmptyMarkers.isEmpty(r.ko()) && !r.ko().contains(ko);
    }

    /** 저장 전 확인: 비어 있지 않고, 실제로 바뀌었고, 바꿀 한국어가 들었고, 태그·자리표시자가 고치기 전과 같다. */
    public static boolean acceptable(String before, String after, String ko) {
        if (before == null || after == null || after.isBlank()) return false;
        String a = after.trim();
        return !a.equals(before.trim()) && a.contains(ko) && PlaceholderCheck.matches(before.trim(), a);
    }

    /** rows 가운데 대상인 행을 고친다. 고치지 못한 행(GPT 오류·거절·확인 실패)은 빠진다. */
    public List<Result> run(List<SheetRow> rows, String src, String ko) {
        Map<String, List<SheetRow>> groups = new LinkedHashMap<>();
        for (SheetRow r : rows) if (target(r, src, ko)) groups.computeIfAbsent(shape(r.src()) + '\u0001' + shape(r.ko()), k -> new ArrayList<>()).add(r);
        if (groups.isEmpty()) return List.of();
        if (groups.size() > MAX_ASKED)
            throw new IllegalArgumentException("바꿀 문장 모양이 " + groups.size() + "가지라 한 번에 " + MAX_ASKED + "가지까지만 합니다. 시트를 좁혀 주세요.");
        List<SheetRow> reps = groups.values().stream().map(g -> g.get(0)).toList();
        Map<Long, String> fixed = ask(reps, src, ko);
        List<Result> out = new ArrayList<>();
        List<SheetRow> alone = new ArrayList<>();
        for (List<SheetRow> g : groups.values()) {
            SheetRow rep = g.get(0);
            String after = fixed.get(rep.id());
            if (after == null) continue;   // 대표를 못 고쳤으면 같은 모양의 나머지도 같은 까닭으로 못 고친다고 본다
            out.add(new Result(rep, after));
            for (SheetRow r : g.subList(1, g.size())) {
                String e = expand(rep.ko(), after, r.ko());
                if (e != null) out.add(new Result(r, e)); else alone.add(r);
            }
        }
        if (!alone.isEmpty()) {
            if (reps.size() + alone.size() > MAX_ASKED) log.warn("용어 바꾸기: 따로 물을 {}행이 한도를 넘어 건너뜀", alone.size());
            else {
                Map<Long, String> more = ask(alone, src, ko);
                for (SheetRow r : alone) if (more.containsKey(r.id())) out.add(new Result(r, more.get(r.id())));
            }
        }
        return out.stream().filter(res -> acceptable(res.row().ko(), res.after(), ko))
                .map(res -> new Result(res.row(), res.after().trim())).toList();
    }

    /** 행 id → GPT가 고친 글. 묶음 하나가 실패해도(GPT 오류) 나머지는 살린다. */
    private Map<Long, String> ask(List<SheetRow> rows, String src, String ko) {
        Map<Long, String> en = new HashMap<>();
        for (Segment s : segments.findAllById(rows.stream().map(SheetRow::id).toList())) en.put(s.getId(), s.getEnText());
        List<List<SheetRow>> chunks = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += CHUNK) chunks.add(rows.subList(i, Math.min(rows.size(), i + CHUNK)));
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(THREADS, chunks.size()));
        try {
            List<Future<List<TranslationResult>>> futures = new ArrayList<>();
            for (List<SheetRow> chunk : chunks) {
                List<FixTermsRow> input = chunk.stream()
                        .map(r -> new FixTermsRow(String.valueOf(r.id()), r.src(), EmptyMarkers.isEmpty(en.get(r.id())) ? null : en.get(r.id()).trim(),
                                r.ko(), Map.of(src, ko)))
                        .toList();
                futures.add(pool.submit(() -> llm.termSwapBatch(input)));
            }
            Map<Long, String> out = new HashMap<>();
            for (Future<List<TranslationResult>> f : futures) {
                try {
                    for (TranslationResult res : f.get()) {
                        if (res.ko() == null) continue;
                        try { out.put(Long.valueOf(res.index()), res.ko()); } catch (NumberFormatException ignored) { }
                    }
                } catch (Exception e) {
                    log.warn("용어 바꾸기 묶음 실패: {}", e.getMessage());
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 숫자를 #으로 접은 꼴. */
    static String shape(String s) { return DIGITS.matcher(s).replaceAll("#"); }

    /**
     * 대표의 번역(repKo)과 고친 글(repAfter)에 든 숫자가 같은 차례로 같으면, 고친 글의 숫자 자리에 이 행 번역(ko)의 숫자를 차례로 넣는다.
     * 아니면 null — 숫자가 어디서 왔는지 알 수 없다.
     */
    static String expand(String repKo, String repAfter, String ko) {
        List<String> a = digits(repKo), b = digits(repAfter), mine = digits(ko);
        if (!a.equals(b) || mine.size() != a.size()) return null;
        Matcher m = DIGITS.matcher(repAfter);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(mine.get(i++)));
        m.appendTail(sb);
        return sb.toString();
    }

    private static List<String> digits(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = DIGITS.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }
}
