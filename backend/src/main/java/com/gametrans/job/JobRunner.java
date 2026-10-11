package com.gametrans.job;

import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class JobRunner {
    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);
    private final JobService jobs;
    private final com.gametrans.llm.LlmUsageService usage;

    public JobRunner(JobService jobs, com.gametrans.llm.LlmUsageService usage) { this.jobs = jobs; this.usage = usage; }

    @Async("jobExecutor")
    public void run(Long jobId, Consumer<JobContext> body) {
        JobContext ctx = new JobContext(jobId, jobs);
        JobThreadContext.set(jobId);
        try {
            body.accept(ctx);
            try {
                String tokens = usage.jobSummaryLine(jobId);
                if (tokens != null) jobs.log(jobId, tokens);
            } catch (RuntimeException e) { log.warn("job {} token summary failed", jobId, e); }
            try {
                jobs.finish(jobId, jobs.stopRequested(jobId) ? TranslationJob.Status.STOPPED : TranslationJob.Status.DONE);
            } catch (Exception e) {
                log.error("job {} failed to record successful finish", jobId, e);
                markFailedBestEffort(jobId);
            }
        } catch (Exception e) {
            log.error("job {} failed", jobId, e);
            try {
                jobs.log(jobId, "실패: " + e.getMessage());
            } catch (Exception logEx) {
                log.error("job {} failed to record failure log", jobId, logEx);
            }
            markFailedBestEffort(jobId);
        } finally { JobThreadContext.clear(); }
    }

    /** finish() 자체가 실패해도 작업이 RUNNING 상태로 남지 않도록 최선 노력으로 FAILED 처리한다. */
    private void markFailedBestEffort(Long jobId) {
        try {
            jobs.finish(jobId, TranslationJob.Status.FAILED);
        } catch (Exception e) {
            log.error("job {} failed to mark FAILED after finish error", jobId, e);
        }
    }
}
