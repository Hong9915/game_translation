package com.gametrans.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.gametrans.reviewer.Reviewer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PresenceServiceTest {
    private final ReviewEvents events = mock(ReviewEvents.class);
    // 발행을 그 자리에서 실행하는 executor — 아래 단언들이 동기적으로 남아 있게 한다.
    private final PresenceService presence = new PresenceService(events, Runnable::run);
    private final Instant t0 = Instant.parse("2026-09-21T00:00:00Z");

    private static Reviewer reviewer(long id, String name) {
        Reviewer r = new Reviewer();
        r.setId(id); r.setName(name); r.setColor("#1f4b99");
        return r;
    }

    @Test
    void publishesOnlyWhenSomethingVisibleChanges() {
        presence.touch(1L, reviewer(7, "지민"), "Words", null, t0);
        presence.touch(1L, reviewer(7, "지민"), "Words", null, t0.plusSeconds(25));   // 단순 heartbeat
        verify(events, times(1)).presence(eq(1L), any());

        presence.touch(1L, reviewer(7, "지민"), "Words", 42L, t0.plusSeconds(30));    // 셀 편집 시작
        verify(events, times(2)).presence(eq(1L), any());
        assertThat(presence.snapshot(1L)).singleElement().satisfies(e -> {
            assertThat(e.name()).isEqualTo("지민");
            assertThat(e.editingSegmentId()).isEqualTo(42L);
        });
    }

    @Test
    void dropsReviewersSilentForOverAMinuteAndWhenTheyLeave() {
        presence.touch(1L, reviewer(7, "지민"), "Words", null, t0);
        presence.touch(1L, reviewer(8, "서연"), "UI", null, t0.plusSeconds(40));
        presence.expire(t0.plusSeconds(61));
        assertThat(presence.snapshot(1L)).extracting(ReviewEvents.PresenceEntry::name).containsExactly("서연");

        presence.leave(1L, 8L);
        assertThat(presence.snapshot(1L)).isEmpty();
        presence.leave(1L, 8L);   // 이미 없는 사람: 조용히 넘어간다
        verify(events, times(4)).presence(eq(1L), any());   // 입장 2 + 만료 1 + 퇴장 1
    }

    @Test
    void projectsAreIsolated() {
        presence.touch(1L, reviewer(7, "지민"), "Words", null, t0);
        assertThat(presence.snapshot(2L)).isEmpty();
        verify(events, never()).presence(eq(2L), any());
    }

    /**
     * 만료 정리는 10초마다 도는 스케줄러 스레드에서 실행된다. SSE 쓰기가 막힌 구독자가 하나라도 있으면
     * 그 스레드가 붙잡혀 모든 프로젝트의 presence 만료가 멈추므로, 발행은 executor로 넘겨야 한다.
     */
    @Test
    void expireHandsPublishingToTheExecutorInsteadOfBlockingTheSweep() {
        List<Runnable> queued = new ArrayList<>();
        PresenceService async = new PresenceService(events, queued::add);
        async.touch(1L, reviewer(7, "지민"), "Words", null, t0);
        async.touch(1L, reviewer(8, "서연"), "UI", null, t0.plusSeconds(40));
        clearInvocations(events);

        async.expire(t0.plusSeconds(61));
        verify(events, never()).presence(eq(1L), any());   // 스윕 스레드에서는 아직 아무것도 보내지 않았다
        assertThat(queued).hasSize(1);

        // task가 큐에 앉아 있는 동안 새 사람이 들어온다. 화면은 presence를 통째로 갈아 끼우므로,
        // 늦게 도는 task가 스윕 시점의 옛 목록을 되살리면 이 사람의 아바타가 사라져 버린다.
        async.touch(1L, reviewer(9, "민준"), "Words", null, t0.plusSeconds(62));
        clearInvocations(events);

        queued.get(0).run();
        verify(events).presence(eq(1L), argThat(list -> names(list).equals(List.of("서연", "민준"))));
    }

    /** 잡 executor가 가득 차 거절해도 그 프로젝트의 만료를 통째로 잃지 않고, 나머지 프로젝트도 계속 알린다. */
    @Test
    void publishesInlineWhenTheExecutorRejectsAndKeepsGoingWithTheRest() {
        List<Runnable> queued = new ArrayList<>();
        AtomicBoolean rejectNext = new AtomicBoolean(true);
        PresenceService async = new PresenceService(events, task -> {
            if (rejectNext.compareAndSet(true, false)) throw new RejectedExecutionException("pool full");
            queued.add(task);
        });
        async.touch(1L, reviewer(7, "지민"), "Words", null, t0);
        async.touch(2L, reviewer(8, "서연"), "UI", null, t0);
        clearInvocations(events);

        async.expire(t0.plusSeconds(61));

        // 거절당한 쪽은 그 자리에서 보냈고, 나머지 한 프로젝트는 평소대로 executor로 갔다.
        assertThat(queued).hasSize(1);
        queued.get(0).run();
        verify(events).presence(eq(1L), argThat(List::isEmpty));
        verify(events).presence(eq(2L), argThat(List::isEmpty));
    }

    /**
     * 만료 정리가 "이제 비었다"고 본 map에 heartbeat가 끼어드는 순간. beforePut 자리에서 마지막 한 명이
     * 나가게 해 그 map이 통째로 사라진 상태를 만든다 — 확인 후 다시 넣지 않으면 방금 들어온 사람이 없어진다.
     */
    @Test
    void touchSurvivesTheProjectMapBeingDroppedMidPut() {
        AtomicBoolean armed = new AtomicBoolean(false);
        AtomicInteger attempts = new AtomicInteger();
        PresenceService racing = new PresenceService(events, Runnable::run) {
            @Override void beforePut(Long projectId) {
                attempts.incrementAndGet();
                if (armed.compareAndSet(true, false)) leave(projectId, 7L);   // 한 번만 끼어든다
            }
        };
        racing.touch(1L, reviewer(7, "지민"), "Words", null, t0);
        armed.set(true);
        attempts.set(0);

        racing.touch(1L, reviewer(8, "서연"), "UI", null, t0);

        assertThat(names(racing.snapshot(1L))).containsExactly("서연");
        assertThat(racing.trackedProjects()).isEqualTo(1);
        assertThat(attempts.get()).isEqualTo(2);   // 첫 시도는 사라진 map에 썼고, 재시도가 살아남았다
    }

    private static List<String> names(List<ReviewEvents.PresenceEntry> list) {
        return list.stream().map(ReviewEvents.PresenceEntry::name).toList();
    }

    /** 아무도 남지 않은 프로젝트의 빈 map은 지운다 — 놔두면 다녀간 프로젝트 수만큼 계속 쌓인다. */
    @Test
    void forgetsProjectsNobodyIsLeftIn() {
        presence.touch(1L, reviewer(7, "지민"), "Words", null, t0);
        presence.touch(2L, reviewer(8, "서연"), "UI", null, t0);
        assertThat(presence.trackedProjects()).isEqualTo(2);

        presence.expire(t0.plusSeconds(61));            // 1번·2번 프로젝트 모두 만료
        assertThat(presence.trackedProjects()).isZero();

        presence.touch(3L, reviewer(9, "민준"), "Words", null, t0);
        presence.leave(3L, 9L);
        assertThat(presence.trackedProjects()).isZero();
    }
}
