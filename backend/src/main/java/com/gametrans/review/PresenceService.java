package com.gametrans.review;

import com.gametrans.review.ReviewEvents.PresenceEntry;
import com.gametrans.reviewer.Reviewer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 누가 지금 검토 화면에 있고 어느 셀을 편집 중인지. 서버 메모리에만 둔다(백엔드는 인스턴스 하나).
 * SSE 연결은 헤더를 못 보내 누구인지 알 수 없으므로, 화면이 주기적으로 보내는 heartbeat로 관리한다.
 */
@Service
public class PresenceService {
    static final Duration TTL = Duration.ofSeconds(60);

    /**
     * 게임 전체 리뷰 화면의 방 번호. 방 map을 프로젝트와 같이 쓰려고 게임 id를 음수로 둔다 — 프로젝트 id는 늘 양수라 겹치지 않고,
     * ReviewEvents.presence가 음수를 보고 게임 스트림으로 보낸다. 파일 화면과 게임 화면 접속자는 섞이지 않는다.
     */
    public static Long gameRoom(Long gameId) { return -gameId; }

    private record Seen(PresenceEntry entry, Instant lastSeen) {}

    private final Map<Long, Map<Long, Seen>> byProject = new ConcurrentHashMap<>();
    private final ReviewEvents events;
    /** 만료 정리에서만 쓰는 발행 스레드. 잡 실행에 쓰는 executor를 그대로 빌린다. */
    private final Executor publisher;

    public PresenceService(ReviewEvents events, @Qualifier("jobExecutor") Executor publisher) {
        this.events = events;
        this.publisher = publisher;
    }

    public List<PresenceEntry> touch(Long projectId, Reviewer who, String sheet, Long editingSegmentId, Instant now) {
        PresenceEntry entry = new PresenceEntry(who.getId(), who.getName(), who.getColor(), sheet, editingSegmentId);
        Seen before = put(projectId, who.getId(), new Seen(entry, now));
        // 단순 heartbeat(보이는 내용이 그대로)는 다른 사람 화면을 깨우지 않는다
        if (before == null || !Objects.equals(before.entry(), entry)) events.presence(projectId, snapshot(projectId));
        return snapshot(projectId);
    }

    /**
     * 만료 정리가 "이제 비었다"고 본 map에 heartbeat가 끼어들면, 그 map이 통째로 지워지면서 방금 들어온
     * 사람이 서버에서 사라진다(다음 heartbeat까지 25초). 그래서 넣은 뒤 그 map이 아직 이 프로젝트의
     * 현재 map인지 확인하고, 아니면 지금 map에 다시 넣는다 — 경합이 끝나면 곧바로 멈춘다(spin이 아니다).
     */
    private Seen put(Long projectId, Long reviewerId, Seen seen) {
        while (true) {
            Map<Long, Seen> current = byProject.computeIfAbsent(projectId, k -> new ConcurrentHashMap<>());
            beforePut(projectId);
            Seen before = current.put(reviewerId, seen);
            if (byProject.get(projectId) == current) return before;
        }
    }

    /** 위 경합을 테스트에서 그대로 재현하기 위한 자리. 테스트가 재정의해 이 틈에 만료 정리를 끼워 넣는다. */
    void beforePut(Long projectId) { }

    public void leave(Long projectId, Long reviewerId) {
        Map<Long, Seen> seen = byProject.get(projectId);
        if (seen == null || seen.remove(reviewerId) == null) return;
        List<PresenceEntry> snapshot = snapshot(projectId);
        forgetIfEmpty(projectId, seen);
        // 요청 스레드라 그대로 보낸다(그 요청만 느려질 뿐 다른 프로젝트에 피해가 없다).
        events.presence(projectId, snapshot);
    }

    /**
     * 60초 넘게 소식 없는 사람을 뺀다. 스케줄러 스레드에서 도는데, SSE 쓰기는 막힌 구독자(노트북 절전,
     * proxy_buffering off) 하나만 있어도 그 자리에서 멈춘다 — 그러면 모든 프로젝트의 만료가 함께 멈추므로
     * 바뀐 프로젝트만 모아 두고 발행 자체는 executor에 넘긴다.
     */
    public void expire(Instant now) {
        List<Long> changed = new ArrayList<>();
        byProject.forEach((projectId, seen) -> {
            if (!seen.values().removeIf(s -> Duration.between(s.lastSeen(), now).compareTo(TTL) > 0)) return;
            changed.add(projectId);
            forgetIfEmpty(projectId, seen);
        });
        for (Long projectId : changed) {
            // 스냅샷은 task 안에서 읽는다 — 여기서 미리 떠 두면 늦게 도는 task가 그 사이 들어온 사람을
            // 지운 옛 목록을 뒤늦게 덮어써 버린다(화면은 presence를 통째로 갈아 끼운다).
            try {
                publisher.execute(() -> events.presence(projectId, snapshot(projectId)));
            } catch (RejectedExecutionException rejected) {
                // 잡 executor가 가득 차 거절했다. 이 프로젝트는 이미 만료 처리가 끝나 다시 알릴 기회가
                // 없으므로, 드물게 여기서 막히더라도 그 자리에서 보내고 나머지 프로젝트를 계속 처리한다.
                events.presence(projectId, snapshot(projectId));
            }
        }
    }

    /** 아무도 남지 않은 프로젝트의 빈 map은 지운다 — 놔두면 다녀간 프로젝트 수만큼 계속 쌓인다. */
    private void forgetIfEmpty(Long projectId, Map<Long, Seen> seen) {
        if (seen.isEmpty()) byProject.remove(projectId, seen);
    }

    /** presence를 들고 있는 프로젝트 수(테스트에서 빈 map이 쌓이지 않는지 확인한다). */
    int trackedProjects() { return byProject.size(); }

    @Scheduled(fixedDelay = 10_000)
    void expireNow() { expire(Instant.now()); }

    /** 그 방(프로젝트 id, 또는 gameRoom)에 그 검수자가 지금 있는가. */
    public boolean isPresent(Long room, Long reviewerId) {
        Map<Long, Seen> seen = byProject.get(room);
        return seen != null && seen.containsKey(reviewerId);
    }

    public List<PresenceEntry> snapshot(Long projectId) {
        Map<Long, Seen> seen = byProject.get(projectId);
        if (seen == null) return List.of();
        return seen.values().stream().map(Seen::entry).sorted(Comparator.comparing(PresenceEntry::id)).toList();
    }
}
