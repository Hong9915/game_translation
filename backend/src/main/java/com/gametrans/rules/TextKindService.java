package com.gametrans.rules;

import com.gametrans.config.AppProperties;
import com.gametrans.job.JobDtos.JobDto;
import com.gametrans.job.JobRunner;
import com.gametrans.job.JobService;
import com.gametrans.job.TranslationJob;
import com.gametrans.llm.LlmClient;
import com.gametrans.llm.LlmClient.KindInput;
import com.gametrans.llm.LlmClient.KindResult;
import com.gametrans.llm.LlmUsageService;
import com.gametrans.rules.RuleLearner.Edit;
import com.gametrans.rules.RuleLearner.Row;
import com.gametrans.rules.RuleText.Clause;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 글 종류 GPT 판단(설계 2026-11-13 §3). 서술식 어미가 있거나 있었던 행만, 행마다 한 번. 결과는 segment.text_kind에 남고
 * 다시 묻지 않는다. 게임 작업(TEXT_KIND)으로 돌려 진행률·멈춤을 쓴다.
 */
@Service
public class TextKindService {
    static final int BATCH = 40;
    static final int TOKENS_IN_PER_ROW = 160, TOKENS_OUT_PER_ROW = 7;
    static final String CODES = "EIQSHTDNU";

    private final RuleSource source;
    private final LlmClient llm;
    private final JdbcTemplate jdbc;
    private final JobService jobs;
    private final JobRunner runner;
    private final LlmUsageService usage;
    private final AppProperties props;

    public TextKindService(RuleSource source, LlmClient llm, JdbcTemplate jdbc, JobService jobs, JobRunner runner,
                           LlmUsageService usage, AppProperties props) {
        this.source = source; this.llm = llm; this.jdbc = jdbc; this.jobs = jobs; this.runner = runner; this.usage = usage; this.props = props;
    }

    /** 아직 종류가 없고, 지금 글이나 손 수정의 직전 글에 서술식 마디가 있는 행. */
    public List<Long> targets(Long gameId) { return targetRows(gameId).stream().map(Row::segmentId).toList(); }

    private List<Row> targetRows(Long gameId) {
        Set<Long> formalBefore = new HashSet<>();
        for (Edit e : source.edits(gameId)) if (RuleText.clauses(e.before()).contains(Clause.FORMAL)) formalBefore.add(e.segmentId());
        List<Row> out = new ArrayList<>();
        for (Row r : source.rows(gameId))
            if (r.textKind() == null && (formalBefore.contains(r.segmentId()) || RuleText.clauses(r.ko()).contains(Clause.FORMAL))) out.add(r);
        return out;
    }

    /** 서술식 마디 끝(태그 지운 글에서). RuleText.clauses의 FORMAL과 같은 끝말. */
    private static final String FORMAL_RE = "(니다|십시오|니까|한다|된다|있다|없다|[가-힣]{2}하세요)\\s*([.!?。…~]|$|\\n|\\\\n)";

    /** targets(gameId).size()와 같은 수를 SQL 한 번으로 — 목록을 열 때마다 게임 전체를 자바로 훑지 않게. */
    public int countTargets(Long gameId) {
        Integer n = jdbc.queryForObject("""
                select count(*) from segment s join project p on p.id = s.project_id
                 where p.game_id = ? and s.skipped = false and s.confirmed = false and s.text_kind is null
                   and (regexp_replace(coalesce(s.latest_ko, ''), '<[^>]+>', '', 'g') ~ ?
                        or exists (select 1 from translation_revision r
                                     join activity a on a.id = r.activity_id and a.kind = 'EDIT'
                                                    and (a.detail->>'segmentId')::bigint = r.segment_id
                                     join lateral (select q.text from translation_revision q
                                                    where q.segment_id = r.segment_id and q.version < r.version
                                                    order by q.version desc limit 1) prev on true
                                    where r.segment_id = s.id and r.author = 'HUMAN'
                                      and regexp_replace(coalesce(prev.text, ''), '<[^>]+>', '', 'g') ~ ?))
                """, Integer.class, gameId, FORMAL_RE, FORMAL_RE);
        return n == null ? 0 : n;
    }

    /** 행 수 × 행당 토큰 × 가벼운 모델 단가. 가격표에 없으면 0. */
    public double estimateUsd(int rows) {
        LlmUsageService.Price p = usage.priceFor(props.openai().lightModelOrDefault());
        if (p == null) return 0;
        return rows * (TOKENS_IN_PER_ROW * p.inputPerM() + TOKENS_OUT_PER_ROW * p.outputPerM()) / 1_000_000.0;
    }

    public JobDto judge(Long gameId) {
        List<Row> rows = targetRows(gameId);
        TranslationJob job = jobs.createForGame(gameId, TranslationJob.Kind.TEXT_KIND);
        runner.run(job.getId(), ctx -> {
            int total = (rows.size() + BATCH - 1) / BATCH, done = 0, saved = 0;
            ctx.log("글 종류 판단: " + rows.size() + "행");
            ctx.progress(0, total);
            for (int i = 0; i < rows.size(); i += BATCH) {
                if (ctx.stopRequested()) break;
                List<Row> batch = rows.subList(i, Math.min(rows.size(), i + BATCH));
                List<KindInput> in = batch.stream().map(r -> new KindInput(String.valueOf(r.segmentId()), r.src(), r.ko())).toList();
                Set<Long> ids = new HashSet<>();
                for (Row r : batch) ids.add(r.segmentId());
                for (KindResult k : llm.classifyTextKinds(in)) {
                    long id;
                    try { id = Long.parseLong(k.id()); } catch (NumberFormatException e) { continue; }
                    String code = k.code() == null ? "" : k.code().trim();
                    if (!ids.contains(id) || code.length() != 1 || CODES.indexOf(code.charAt(0)) < 0) continue;
                    saved += jdbc.update("update segment set text_kind = ? where id = ? and text_kind is null", code, id);
                }
                ctx.progress(++done, total);
            }
            ctx.log("저장 " + saved + "행");
        });
        return jobs.get(job.getId());
    }
}
