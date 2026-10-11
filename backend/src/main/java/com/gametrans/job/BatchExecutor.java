package com.gametrans.job;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 작업 하나 안에서 GPT 묶음 호출을 여러 개 동시에 돌린다.
 * 결과는 입력 순서대로 돌려주고, 묶음 하나가 끝날 때마다 진행률을 올린다.
 * 중지 요청이 오면 아직 시작 안 한 묶음은 건너뛰고(null), 묶음 하나라도 예외가 나면 나머지를 취소하고 예외를 던진다.
 */
@Component
public class BatchExecutor {
    private final int concurrency;

    public BatchExecutor(@Value("${app.llm-concurrency:4}") int concurrency) {
        this.concurrency = Math.max(1, concurrency);
    }

    public int concurrency() { return concurrency; }

    public <T, R> List<R> map(JobContext ctx, List<T> chunks, BiFunction<Integer, T, R> worker) {
        if (chunks.isEmpty()) return List.of();
        int threads = Math.min(concurrency, chunks.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "llm-batch-" + ctx.jobId());
            t.setDaemon(true);
            return t;
        });
        AtomicInteger done = new AtomicInteger();
        List<Future<R>> futures = new ArrayList<>(chunks.size());
        try {
            for (int i = 0; i < chunks.size(); i++) {
                final int idx = i;
                final T chunk = chunks.get(i);
                futures.add(pool.submit(() -> {
                    if (ctx.stopRequested()) return null;
                    JobThreadContext.set(ctx.jobId());  // 이 스레드의 GPT 호출을 이 작업에 귀속
                    try {
                        R r = worker.apply(idx, chunk);
                        ctx.progress(done.incrementAndGet(), chunks.size());
                        return r;
                    } finally { JobThreadContext.clear(); }
                }));
            }
            List<R> out = new ArrayList<>(chunks.size());
            for (Future<R> f : futures) out.add(f.get());
            return out;
        } catch (ExecutionException e) {
            for (Future<R> f : futures) f.cancel(true);
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("작업이 인터럽트되었습니다", e);
        } finally {
            pool.shutdownNow();
        }
    }
}
