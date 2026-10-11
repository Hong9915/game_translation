package com.gametrans.llm;

import com.gametrans.config.AppProperties;
import com.gametrans.job.JobThreadContext;
import com.gametrans.job.TranslationJobRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 토큰 사용량 저장·집계. 비용은 설정된 단가(백만 토큰당 USD, 입력/출력)로 계산한 추정치다.
 * 단가는 OPENAI_PRICES 로 "gpt-5=1.25/10,gpt-5-mini=0.25/2" 형식.
 */
@Service
public class LlmUsageService implements UsageSink {
    private static final Logger log = LoggerFactory.getLogger(LlmUsageService.class);
    public record StageUsage(String stage, String model, long calls, long promptTokens, long completionTokens, long reasoningTokens, double costUsd) {}
    public record UsageSummary(long calls, long promptTokens, long completionTokens, long reasoningTokens, double costUsd, boolean priced, List<StageUsage> byStage) {}
    public record Price(double inputPerM, double outputPerM) {}

    private final LlmUsageRepository repo;
    private final TranslationJobRepository jobs;
    private final Map<String, Price> prices;

    public LlmUsageService(LlmUsageRepository repo, TranslationJobRepository jobs, AppProperties props) {
        this.repo = repo;
        this.jobs = jobs;
        this.prices = parsePrices(props.openai().prices());
    }

    @Override
    public void record(Usage u) {
        try {
            LlmUsage row = new LlmUsage();
            Long jobId = JobThreadContext.get();
            row.setJobId(jobId);
            if (jobId != null) jobs.findById(jobId).ifPresent(j -> row.setProjectId(j.getProjectId()));
            row.setStage(u.stage()); row.setModel(u.model());
            row.setPromptTokens(u.promptTokens()); row.setCompletionTokens(u.completionTokens()); row.setReasoningTokens(u.reasoningTokens());
            repo.save(row);
        } catch (RuntimeException e) {
            log.warn("token usage not recorded: {}", e.toString());  // 사용량 기록 실패가 번역을 막으면 안 된다
        }
    }

    public UsageSummary project(Long projectId) {
        List<StageUsage> out = new ArrayList<>();
        for (Object[] r : repo.sumByStageAndModel(projectId))
            out.add(stage((String) r[0], (String) r[1], (Long) r[2], num(r[3]), num(r[4]), num(r[5])));
        return summarize(out);
    }

    public UsageSummary all() {
        List<StageUsage> out = new ArrayList<>();
        for (Object[] r : repo.sumAllByModel())
            out.add(stage("ALL", (String) r[0], (Long) r[1], num(r[2]), num(r[3]), num(r[4])));
        return summarize(out);
    }

    /** 작업 하나의 토큰 합계 로그 문구. 사용량이 없으면 null. */
    public String jobSummaryLine(Long jobId) {
        List<Object[]> r = repo.sumByJob(jobId);
        if (r.isEmpty()) return null;
        long in = num(r.get(0)[0]), outT = num(r.get(0)[1]);
        if (in == 0 && outT == 0) return null;
        return String.format("토큰 사용: 입력 %,d · 출력 %,d", in, outT);
    }

    private StageUsage stage(String stage, String model, long calls, long in, long outT, long reasoning) {
        Price p = priceFor(model);
        double cost = p == null ? 0 : (in * p.inputPerM() + outT * p.outputPerM()) / 1_000_000.0;
        return new StageUsage(stage, model, calls, in, outT, reasoning, cost);
    }

    private UsageSummary summarize(List<StageUsage> byStage) {
        long calls = 0, in = 0, outT = 0, reasoning = 0; double cost = 0; boolean priced = true;
        for (StageUsage s : byStage) {
            calls += s.calls(); in += s.promptTokens(); outT += s.completionTokens(); reasoning += s.reasoningTokens(); cost += s.costUsd();
            if (priceFor(s.model()) == null) priced = false;
        }
        return new UsageSummary(calls, in, outT, reasoning, cost, priced, byStage);
    }

    Price priceFor(String model) {
        if (model == null) return null;
        String m = model.toLowerCase(Locale.ROOT);
        if (prices.containsKey(m)) return prices.get(m);
        // 날짜 접미사(gpt-5-2026-01-01)나 변형은 가장 긴 접두 일치로
        Price best = null; int bestLen = -1;
        for (Map.Entry<String, Price> e : prices.entrySet())
            if (m.startsWith(e.getKey()) && e.getKey().length() > bestLen) { best = e.getValue(); bestLen = e.getKey().length(); }
        return best;
    }

    static Map<String, Price> parsePrices(String spec) {
        Map<String, Price> out = new LinkedHashMap<>();
        if (spec == null) return out;
        for (String part : spec.split(",")) {
            String[] kv = part.trim().split("=");
            if (kv.length != 2) continue;
            String[] io = kv[1].split("/");
            if (io.length != 2) continue;
            try { out.put(kv[0].trim().toLowerCase(Locale.ROOT), new Price(Double.parseDouble(io[0].trim()), Double.parseDouble(io[1].trim()))); }
            catch (NumberFormatException ignored) { /* 잘못된 항목은 건너뜀 */ }
        }
        return out;
    }

    private static long num(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
}
