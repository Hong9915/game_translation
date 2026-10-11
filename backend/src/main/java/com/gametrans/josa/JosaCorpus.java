package com.gametrans.josa;

import com.gametrans.normalize.SegmentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 게임별 한글 낱말 빈도. 사전 명사 제안(장로·모험가)을 거를 때 상대 비교만 하므로 1시간 낡아도 된다(설계 §3). */
@Component
public class JosaCorpus {
    static final Duration TTL = Duration.ofHours(1);
    private static final Pattern WORD = Pattern.compile("[가-힣]+");

    private record Entry(Instant at, Map<String, Integer> counts) {}

    private final SegmentRepository segments;
    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();

    public JosaCorpus(SegmentRepository segments) { this.segments = segments; }

    public Map<String, Integer> counts(Long gameId) {
        Entry e = cache.get(gameId);
        if (e == null || e.at().plus(TTL).isBefore(Instant.now())) {
            e = new Entry(Instant.now(), count(segments.latestKoInGame(gameId)));
            cache.put(gameId, e);
        }
        return e.counts();
    }

    static Map<String, Integer> count(List<String> texts) {
        Map<String, Integer> out = new HashMap<>();
        for (String t : texts) {
            Matcher m = WORD.matcher(t);
            while (m.find()) out.merge(m.group(), 1, Integer::sum);
        }
        return out;
    }
}
