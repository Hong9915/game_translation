package com.gametrans.job;

public class JobContext {
    private final Long jobId;
    private final JobService jobs;

    JobContext(Long jobId, JobService jobs) { this.jobId = jobId; this.jobs = jobs; }

    public Long jobId() { return jobId; }
    public void log(String line) { jobs.log(jobId, line); }
    public void progress(int done, int total) { jobs.progress(jobId, done, total); }
    public boolean stopRequested() { return jobs.stopRequested(jobId); }
}
