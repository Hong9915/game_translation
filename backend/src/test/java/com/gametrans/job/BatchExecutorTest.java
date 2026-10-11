package com.gametrans.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BatchExecutorTest {
    JobService jobs = mock(JobService.class);
    JobContext ctx = new JobContext(7L, jobs);

    @Test
    void runsChunksConcurrentlyAndKeepsInputOrder() throws Exception {
        BatchExecutor ex = new BatchExecutor(3);
        CountDownLatch allStarted = new CountDownLatch(3);
        List<String> out = ex.map(ctx, List.of("a", "b", "c"), (i, s) -> {
            allStarted.countDown();
            // 셋이 동시에 돌지 않으면 여기서 막혀 타임아웃 → 병렬성 검증
            try { if (!allStarted.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("not parallel"); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            return s.toUpperCase() + i;
        });
        assertThat(out).containsExactly("A0", "B1", "C2");
        verify(jobs).progress(eq(7L), eq(3), eq(3));
    }

    @Test
    void reportsProgressPerFinishedChunk() {
        BatchExecutor ex = new BatchExecutor(1);
        ex.map(ctx, List.of(1, 2), (i, n) -> n);
        verify(jobs).progress(7L, 1, 2);
        verify(jobs).progress(7L, 2, 2);
    }

    @Test
    void skipsChunksNotYetStartedAfterStopRequest() {
        BatchExecutor ex = new BatchExecutor(1);
        AtomicInteger calls = new AtomicInteger();
        when(jobs.stopRequested(7L)).thenAnswer(inv -> calls.get() >= 1);
        List<Integer> out = ex.map(ctx, List.of(1, 2, 3), (i, n) -> { calls.incrementAndGet(); return n * 10; });
        assertThat(out).containsExactly(10, null, null);
    }

    @Test
    void propagatesFirstFailureAndCancelsTheRest() {
        BatchExecutor ex = new BatchExecutor(2);
        assertThatThrownBy(() -> ex.map(ctx, List.of(1, 2, 3, 4), (i, n) -> {
            if (n == 2) throw new IllegalStateException("boom");
            return n;
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");
        verify(jobs, org.mockito.Mockito.never()).progress(eq(7L), eq(4), anyInt());
    }
}
