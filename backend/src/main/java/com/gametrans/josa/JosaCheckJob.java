package com.gametrans.josa;

import com.gametrans.normalize.SegmentRepository;
import com.gametrans.normalize.SegmentRepository.JosaPending;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * josa_checked=false인 행을 묶어 보조 컨테이너에 보내고 제안을 저장한다(설계 2026-10-07 §3).
 * 저장 경로와 떨어져 있어서 보조 컨테이너가 죽어도 번역 저장은 막히지 않는다 — 검사만 다음 차례로 미뤄진다.
 */
@Component
public class JosaCheckJob {
    static final int BATCH = 500;
    private static final Logger log = LoggerFactory.getLogger(JosaCheckJob.class);

    private final SegmentRepository segments;
    private final JosaLive live;
    private final JosaClient client;
    private final boolean scheduled;
    private long lastWarnAt;

    public JosaCheckJob(SegmentRepository segments, JosaLive live, JosaClient client,
                        @Value("${app.josa.scheduled:true}") boolean scheduled) {
        this.segments = segments;
        this.live = live;
        this.client = client;
        this.scheduled = scheduled;
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 30_000)
    public void tick() {
        if (!scheduled || !client.enabled()) return;
        safeRun();
    }

    /** 실패하면 경고만 남기고(같은 실패가 이어지면 1분에 한 번) 0을 돌려준다. */
    public int safeRun() {
        try {
            return runOnce();
        } catch (RuntimeException e) {
            long now = System.currentTimeMillis();
            if (now - lastWarnAt > 60_000) {
                log.warn("조사 검사를 건너뜁니다: {}", e.toString());
                lastWarnAt = now;
            }
            return 0;
        }
    }

    /** 한 차례. 저장한 행 수(빈 번역 정리 제외). */
    public int runOnce() {
        segments.markBlankJosaChecked();
        List<JosaPending> rows = segments.josaPending(BATCH);
        Map<Long, List<JosaPending>> byGame = new LinkedHashMap<>();
        for (JosaPending r : rows) byGame.computeIfAbsent(r.getGameId(), g -> new ArrayList<>()).add(r);
        int saved = 0;
        for (Map.Entry<Long, List<JosaPending>> e : byGame.entrySet()) {
            List<JosaPending> part = e.getValue();
            List<List<JosaHint>> hints = live.check(e.getKey(), part.stream().map(JosaPending::getLatestKo).toList());
            for (int i = 0; i < part.size(); i++) {
                JosaPending r = part.get(i);
                List<JosaHint> keep = hints.get(i);
                saved += keep.isEmpty() ? segments.saveJosaNone(r.getId(), r.getLatestKo())
                                        : segments.saveJosa(r.getId(), r.getLatestKo(), JosaHints.format(keep));
            }
        }
        return saved;
    }
}
