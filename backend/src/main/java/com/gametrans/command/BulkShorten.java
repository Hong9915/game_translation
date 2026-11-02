package com.gametrans.command;

import com.gametrans.game.Game;
import com.gametrans.game.GameService;
import com.gametrans.glossary.GlossaryPrompt;
import com.gametrans.llm.LlmClient;
import com.gametrans.llm.LlmClient.ShortenOptionsRequest;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.text.EmptyMarkers;
import com.gametrans.text.LengthCheck;
import com.gametrans.text.PlaceholderCheck;
import com.gametrans.translate.QuickTranslateService;
import com.gametrans.translate.RowShortenService;
import com.gametrans.translate.TranslateDtos.ShortenCandidate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 명령으로 고치기의 "길이 줄이기"(2026-10-13). 길이 기준(원문 × 비율)을 넘는 행을 행마다 셀 우클릭 "짧게 줄이기"와 같은 GPT 호출로
 * 줄여 후보 하나를 고른다. GPT 결과라 다시 돌리면 달라지므로, 저장은 다시 계산하지 않고 acceptable로 확인만 한다.
 * 화면이 묶음(MAX_ROWS)씩 나눠 불러 진행을 보인다.
 */
@Service
public class BulkShorten {
    private static final Logger log = LoggerFactory.getLogger(BulkShorten.class);
    static final int MAX_ROWS = 40;
    static final int THREADS = 4;

    /** after가 null이면 줄이지 못한 행. */
    public record Result(SheetRow row, int target, String after) {}

    private final SegmentRepository segments;
    private final GameService games;
    private final QuickTranslateService quick;
    private final LlmClient llm;

    public BulkShorten(SegmentRepository segments, GameService games, QuickTranslateService quick, LlmClient llm) {
        this.segments = segments;
        this.games = games;
        this.quick = quick;
        this.llm = llm;
    }

    public static int target(String src, double ratio) { return Math.max(1, (int) Math.floor(LengthCheck.count(src) * ratio)); }

    /** 줄일 대상: 확정이 아니고 번역이 있으며 길이 기준을 넘는 행. */
    public static boolean over(SheetRow r, double ratio) {
        return !r.confirmed() && !EmptyMarkers.isEmpty(r.ko()) && LengthCheck.flagged(r.src(), r.ko(), ratio);
    }

    /** rows 가운데 대상인 행만 줄인다. 행 하나가 실패해도(GPT 오류) 나머지는 살린다. */
    public List<Result> run(List<SheetRow> rows, double ratio, Long gameId) {
        List<SheetRow> targets = rows.stream().filter(r -> over(r, ratio)).toList();
        if (targets.size() > MAX_ROWS) throw new IllegalArgumentException("한 번에 " + MAX_ROWS + "행까지만 줄입니다.");
        if (targets.isEmpty()) return List.of();
        Game game = games.get(gameId);
        Map<Long, String> en = new HashMap<>();
        for (Segment s : segments.findAllById(targets.stream().map(SheetRow::id).toList())) en.put(s.getId(), s.getEnText());
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(THREADS, targets.size()));
        try {
            List<Future<Result>> futures = new ArrayList<>();
            for (SheetRow r : targets) futures.add(pool.submit(() -> one(r, ratio, game, en.get(r.id()))));
            List<Result> out = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                try { out.add(futures.get(i).get()); }
                catch (Exception e) {
                    log.warn("길이 줄이기 실패 {}: {}", targets.get(i).id(), e.getMessage());
                    out.add(new Result(targets.get(i), target(targets.get(i).src(), ratio), null));
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private Result one(SheetRow r, double ratio, Game game, String enText) {
        String ko = r.ko().trim();
        int target = target(r.src(), ratio);
        QuickTranslateService.Terms terms = quick.termsFor(game, r.src());
        String en = EmptyMarkers.isEmpty(enText) ? null : enText.trim();
        List<String> options = llm.shortenOptions(new ShortenOptionsRequest(r.src(), en, ko, target, terms.withNotes(),
                GlossaryPrompt.instructions(null, terms.notes())));
        return new Result(r, target, pick(RowShortenService.candidates(ko, options), target));
    }

    /** 목표 안에 드는 후보 가운데 가장 긴 것(뜻을 가장 덜 잃는다). 목표 안에 드는 게 없으면 가장 짧은 것. 후보가 없으면 null. */
    static String pick(List<ShortenCandidate> candidates, int target) {
        ShortenCandidate fit = null, shortest = null;
        for (ShortenCandidate c : candidates) {
            if (c.len() <= target && (fit == null || c.len() > fit.len())) fit = c;
            if (shortest == null || c.len() < shortest.len()) shortest = c;
        }
        return fit != null ? fit.ko() : shortest == null ? null : shortest.ko();
    }

    /** 저장 전 확인: 비어 있지 않고, 실제로 짧아졌고, 태그·자리표시자가 줄이기 전과 같다. */
    public static boolean acceptable(String before, String after) {
        if (before == null || after == null || after.isBlank()) return false;
        return LengthCheck.count(after) < LengthCheck.count(before) && PlaceholderCheck.matches(before.trim(), after);
    }
}
