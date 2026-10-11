package com.gametrans.command;

import com.gametrans.josa.JosaHint;
import com.gametrans.josa.JosaHints;
import com.gametrans.text.PlaceholderCheck;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 명령으로 고치기 작업 목록(설계 2026-10-08 §1). 행마다 지금 번역 → Op1 → Op2 … 를 거친 글이 지금 번역과 다르면 후보다.
 * 확정 행은 고치지 않는다(같은 원문 통일의 목표를 정할 때만 읽는다). Kiwi 사이드카는 Sidecar로 받는다.
 * 조사 맞추기(JOSA)와 태그 띄어쓰기(TAG_SPACING)는 맨 앞에 있으면 모든 대상 행에, 다른 작업 뒤에 있으면 앞 작업이 이미 바꾼 행에만 적용한다.
 */
public final class Pipeline {
    static final int MAX_OPS = 6;
    static final int BATCH = 200;
    /** 조사 맞추기에 쓰는 종류. 태그 뒤(TAG)는 "모두 적용" 기본값처럼 뺀다. */
    static final Set<String> JOSA_KINDS = Set.of("HANGUL", "NUMBER");
    public static final String TAG_BROKEN = "태그·자리표시자 짝이 깨집니다";

    /** Kiwi 사이드카. 꺼져 있거나 실패하면 IllegalStateException. */
    public interface Sidecar {
        List<String> tagSpace(List<String> texts);
        List<List<JosaHint>> josa(List<String> texts);
    }

    /** kind = JOSA(바뀐 글에 새로 생긴 조사 제안 — 원래 번역에 있던 제안은 뺀다) | TAG(태그·자리표시자 짝이 깨짐). */
    public record Warning(String kind, String text) {}
    /** by = 이 행을 바꾼 Op 종류(차례대로, 겹침 없이). */
    public record Change(SheetRow row, String after, List<String> by, List<Warning> warnings) {}

    private final List<OpSpec> ops;
    private final List<CommandRule> rules;   // ops와 같은 자리, REPLACE가 아니면 null

    private Pipeline(List<OpSpec> ops, List<CommandRule> rules) {
        this.ops = ops;
        this.rules = rules;
    }

    public static Pipeline compile(List<OpSpec> ops) {
        if (ops == null || ops.isEmpty()) throw new IllegalArgumentException("작업이 없습니다.");
        if (ops.size() > MAX_OPS) throw new IllegalArgumentException("작업이 너무 많습니다.");
        List<CommandRule> rules = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            OpSpec op = ops.get(i);
            if (op == null || op.kind() == null || !OpSpec.KINDS.contains(op.kind()))
                throw new IllegalArgumentException("모르는 작업입니다: " + (op == null ? null : op.kind()));
            if (OpSpec.UNIFY_SAME_SOURCE.equals(op.kind()) && i > 0)
                throw new IllegalArgumentException("같은 원문 통일은 맨 앞에만 둘 수 있습니다.");
            rules.add(OpSpec.REPLACE.equals(op.kind()) ? CommandRule.compile(op.filter(), op.steps()) : null);
        }
        return new Pipeline(List.copyOf(ops), rules);
    }

    public List<OpSpec> ops() { return ops; }

    /** only가 있으면 그 행만 후보로 본다(저장 때 다시 계산). 같은 원문 통일의 목표는 언제나 rows 전체로 정한다. */
    public List<Change> run(List<SheetRow> rows, Set<Long> only, Sidecar sidecar) {
        return run(rows, only, sidecar, true);
    }

    /** warn이 거짓이면 경고(조사·태그 짝)를 셈하지 않는다 — 저장 때 다시 계산할 때는 after만 본다. */
    public List<Change> run(List<SheetRow> rows, Set<Long> only, Sidecar sidecar, boolean warn) {
        List<SheetRow> targets = rows.stream().filter(r -> !r.confirmed() && (only == null || only.contains(r.id()))).toList();
        Map<Long, String> cur = new LinkedHashMap<>();
        Map<Long, List<String>> by = new HashMap<>();
        for (SheetRow r : targets) cur.put(r.id(), r.ko());
        for (int i = 0; i < ops.size(); i++) {
            String kind = ops.get(i).kind();
            switch (kind) {
                case OpSpec.UNIFY_SAME_SOURCE -> {
                    Map<String, String> goal = unifyTargets(rows);
                    for (SheetRow r : targets) set(cur, by, r.id(), goal.get(r.src()), kind);
                }
                case OpSpec.REPLACE -> {
                    CommandRule rule = rules.get(i);
                    for (SheetRow r : targets) {
                        String c = cur.get(r.id());
                        if (rule.matches(r.src(), c)) set(cur, by, r.id(), rule.apply(c), kind);
                    }
                }
                case OpSpec.TAG_SPACING -> tagSpacing(scope(i, targets, by), cur, by, sidecar);
                case OpSpec.JOSA -> josa(scope(i, targets, by), cur, by, sidecar);
                default -> throw new IllegalStateException(kind);
            }
        }
        boolean josaOp = ops.stream().anyMatch(o -> OpSpec.JOSA.equals(o.kind()));
        List<SheetRow> changed = targets.stream().filter(r -> !cur.get(r.id()).equals(r.ko())).toList();
        Map<Long, List<Warning>> warnings = new HashMap<>();
        if (warn && !josaOp) josaWarnings(changed, cur, warnings, sidecar);
        if (warn) for (SheetRow r : changed)
            if (PlaceholderCheck.matches(r.src(), r.ko()) && !PlaceholderCheck.matches(r.src(), cur.get(r.id())))
                warnings.computeIfAbsent(r.id(), k -> new ArrayList<>()).add(new Warning("TAG", TAG_BROKEN));
        List<Change> out = new ArrayList<>();
        for (SheetRow r : changed)
            out.add(new Change(r, cur.get(r.id()), by.getOrDefault(r.id(), List.of()), warnings.getOrDefault(r.id(), List.of())));
        return out;
    }

    /** 맨 앞 작업이면 모든 대상 행, 뒤에 놓였으면 앞 작업이 이미 바꾼 행만. */
    private static List<SheetRow> scope(int opIndex, List<SheetRow> targets, Map<Long, List<String>> by) {
        return opIndex == 0 ? targets : targets.stream().filter(r -> by.containsKey(r.id())).toList();
    }

    private static void set(Map<Long, String> cur, Map<Long, List<String>> by, Long id, String text, String kind) {
        if (text == null || text.isBlank() || text.equals(cur.get(id))) return;
        cur.put(id, text);
        List<String> b = by.computeIfAbsent(id, k -> new ArrayList<>());
        if (!b.contains(kind)) b.add(kind);
    }

    private static void tagSpacing(List<SheetRow> targets, Map<Long, String> cur, Map<Long, List<String>> by, Sidecar sidecar) {
        List<SheetRow> tagged = targets.stream().filter(r -> cur.get(r.id()).matches("(?s).*<[^>]+>.*")).toList();
        for (int from = 0; from < tagged.size(); from += BATCH) {
            List<SheetRow> batch = tagged.subList(from, Math.min(tagged.size(), from + BATCH));
            List<String> spaced = sidecar.tagSpace(batch.stream().map(r -> cur.get(r.id())).toList());
            for (int i = 0; i < batch.size(); i++) {
                String before = cur.get(batch.get(i).id()), after = spaced.get(i);
                if (after != null && squash(before).equals(squash(after))) set(cur, by, batch.get(i).id(), after, OpSpec.TAG_SPACING);
            }
        }
    }

    /** 글이 그대로이고 이미 검사된 행은 저장된 제안을, 나머지는 사이드카로 새로 검사한다(저장 제안의 at은 지금 번역 기준이다). */
    private static void josa(List<SheetRow> targets, Map<Long, String> cur, Map<Long, List<String>> by, Sidecar sidecar) {
        List<SheetRow> live = new ArrayList<>();
        for (SheetRow r : targets) {
            String c = cur.get(r.id());
            if (c.equals(r.ko()) && Boolean.TRUE.equals(r.josaChecked())) {
                if (!r.josaIgnored()) set(cur, by, r.id(), JosaHints.applyTo(c, JosaHints.parse(r.josaHint()), JOSA_KINDS), OpSpec.JOSA);
            } else live.add(r);
        }
        for (int from = 0; from < live.size(); from += BATCH) {
            List<SheetRow> batch = live.subList(from, Math.min(live.size(), from + BATCH));
            List<List<JosaHint>> hints = sidecar.josa(batch.stream().map(r -> cur.get(r.id())).toList());
            for (int i = 0; i < batch.size(); i++) {
                Long id = batch.get(i).id();
                set(cur, by, id, JosaHints.applyTo(cur.get(id), hints.get(i), JOSA_KINDS), OpSpec.JOSA);
            }
        }
    }

    /**
     * 바뀐 글에 새로 생긴 조사 제안만 경고한다: 바뀐 글의 제안에서 원래 번역에 이미 있던 제안을 label 기준(여러 번 셈)으로 뺀다.
     * 원래 번역의 제안은 검사된 행이면 저장된 josa_hint, 아니면 같은 사이드카 호출로 함께 검사한다. 조사 무시 행은 경고하지 않는다.
     * 사이드카가 안 되면 경고만 건너뛴다 — 찾아 바꾸기 미리 보기는 막지 않는다.
     */
    private static void josaWarnings(List<SheetRow> changed, Map<Long, String> cur, Map<Long, List<Warning>> out, Sidecar sidecar) {
        List<SheetRow> watched = changed.stream().filter(r -> !r.josaIgnored()).toList();
        int rowsPerCall = BATCH / 2;     // 행마다 바뀐 글과 원래 글 두 문장까지 보낸다
        try {
            for (int from = 0; from < watched.size(); from += rowsPerCall) {
                List<SheetRow> batch = watched.subList(from, Math.min(watched.size(), from + rowsPerCall));
                List<String> texts = new ArrayList<>();
                for (SheetRow r : batch) texts.add(cur.get(r.id()));
                Map<Long, Integer> originalAt = new HashMap<>();
                for (SheetRow r : batch)
                    if (!Boolean.TRUE.equals(r.josaChecked())) { originalAt.put(r.id(), texts.size()); texts.add(r.ko()); }
                List<List<JosaHint>> hints = sidecar.josa(texts);
                for (int i = 0; i < batch.size(); i++) {
                    SheetRow r = batch.get(i);
                    Integer at = originalAt.get(r.id());
                    Map<String, Integer> before = new HashMap<>();
                    for (String l : labels(r.ko(), at == null ? JosaHints.parse(r.josaHint()) : hints.get(at))) before.merge(l, 1, Integer::sum);
                    for (String l : labels(texts.get(i), hints.get(i))) {
                        if (before.getOrDefault(l, 0) > 0) before.merge(l, -1, Integer::sum);
                        else out.computeIfAbsent(r.id(), k -> new ArrayList<>()).add(new Warning("JOSA", l));
                    }
                }
            }
        } catch (IllegalStateException e) {
            out.values().forEach(l -> l.removeIf(w -> "JOSA".equals(w.kind())));
        }
    }

    private static List<String> labels(String text, List<JosaHint> hints) {
        return hints.stream().filter(h -> JOSA_KINDS.contains(h.kind())).map(h -> JosaHints.label(text, h)).toList();
    }

    /** 원문마다 맞출 번역: 확정 행 번역이 하나로 같으면 그것, 확정이 없으면 가장 많이 쓴 번역(1위 동점이면 없음). 확정끼리 다르면 없음. */
    static Map<String, String> unifyTargets(List<SheetRow> rows) {
        Map<String, List<SheetRow>> groups = new LinkedHashMap<>();
        for (SheetRow r : rows) if (r.src() != null && !r.src().isBlank()) groups.computeIfAbsent(r.src(), k -> new ArrayList<>()).add(r);
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, List<SheetRow>> g : groups.entrySet()) {
            List<String> fixed = g.getValue().stream().filter(SheetRow::confirmed).map(SheetRow::ko).distinct().toList();
            if (fixed.size() == 1) { out.put(g.getKey(), fixed.get(0)); continue; }
            if (fixed.size() > 1) continue;
            Map<String, Integer> count = new LinkedHashMap<>();
            for (SheetRow r : g.getValue()) count.merge(r.ko(), 1, Integer::sum);
            List<Map.Entry<String, Integer>> sorted = count.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).toList();
            if (sorted.size() > 1 && sorted.get(0).getValue().equals(sorted.get(1).getValue())) continue;
            out.put(g.getKey(), sorted.get(0).getKey());
        }
        return out;
    }

    private static String squash(String s) { return s.replaceAll("\\s+", ""); }
}
