package com.gametrans.job;

/** 지금 이 스레드가 어느 작업을 위해 GPT를 부르는지. 토큰 사용량을 작업·프로젝트에 귀속시키는 데 쓴다. */
public final class JobThreadContext {
    private static final ThreadLocal<Long> JOB = new ThreadLocal<>();
    private JobThreadContext() {}
    public static void set(Long jobId) { if (jobId == null) JOB.remove(); else JOB.set(jobId); }
    public static Long get() { return JOB.get(); }
    public static void clear() { JOB.remove(); }
}
