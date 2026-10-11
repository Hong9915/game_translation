package com.gametrans.job;

import com.gametrans.common.NotFoundException;
import com.gametrans.job.JobDtos.Activity;
import com.gametrans.job.JobDtos.ActivitySnapshot;
import com.gametrans.job.JobDtos.JobDto;
import com.gametrans.job.JobDtos.LogLine;
import com.gametrans.job.JobDtos.Progress;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class JobService {
    private final TranslationJobRepository jobs;
    private final JobLogRepository logs;
    private final JobEventBus bus;
    private final Set<Long> stopRequests = ConcurrentHashMap.newKeySet();

    public JobService(TranslationJobRepository jobs, JobLogRepository logs, JobEventBus bus) {
        this.jobs = jobs;
        this.logs = logs;
        this.bus = bus;
    }

    @Transactional
    public TranslationJob create(Long projectId, TranslationJob.Kind kind) {
        if (jobs.findFirstByProjectIdAndStatus(projectId, TranslationJob.Status.RUNNING).isPresent())
            throw new IllegalStateException("이미 실행 중인 작업이 있습니다. 중지 후 다시 시도하세요.");
        TranslationJob j = new TranslationJob();
        j.setProjectId(projectId);
        j.setKind(kind);
        TranslationJob saved = jobs.save(j);
        bus.publishProject(projectId, Activity.of("started", saved, null));
        return saved;
    }

    /** 프로젝트가 아니라 게임에 붙는 작업(용어집 정리). 게임당 하나만 돈다. */
    @Transactional
    public TranslationJob createForGame(Long gameId, TranslationJob.Kind kind) {
        if (jobs.findFirstByGameIdAndStatus(gameId, TranslationJob.Status.RUNNING).isPresent())
            throw new IllegalStateException("이 게임에 이미 실행 중인 작업이 있습니다. 끝난 뒤 다시 시도하세요.");
        TranslationJob j = new TranslationJob();
        j.setGameId(gameId);
        j.setKind(kind);
        return jobs.save(j);
    }

    @Transactional
    public void log(Long jobId, String line) {
        JobLog l = new JobLog();
        l.setJobId(jobId);
        l.setSeq((int) logs.countByJobId(jobId) + 1);
        l.setLine(line);
        logs.save(l);
        bus.publish(jobId, "log", new LogLine(line));
        TranslationJob j = find(jobId);
        if (j.getProjectId() != null) bus.publishProject(j.getProjectId(), Activity.of("log", j, line));
    }

    @Transactional
    public void progress(Long jobId, int done, int total) {
        TranslationJob j = find(jobId);
        j.setDoneBatches(done);
        j.setTotalBatches(total);
        bus.publish(jobId, "progress", new Progress(done, total));
        if (j.getProjectId() != null) bus.publishProject(j.getProjectId(), Activity.of("progress", j, null));
    }

    @Transactional
    public void finish(Long jobId, TranslationJob.Status status) {
        TranslationJob j = find(jobId);
        j.setStatus(status);
        j.setFinishedAt(Instant.now());
        stopRequests.remove(jobId);
        bus.publish(jobId, "done", get(jobId));
        bus.complete(jobId);
        if (j.getProjectId() != null) bus.publishProject(j.getProjectId(), Activity.of("done", j, null));
    }

    public void requestStop(Long jobId) {
        TranslationJob j = find(jobId);
        if (j.isTerminal()) return;
        stopRequests.add(jobId);
        // race guard: finish() may have run (and removed nothing, since we hadn't added yet)
        // between the check above and the add; re-check and undo if the job already finished.
        if (find(jobId).isTerminal()) stopRequests.remove(jobId);
    }

    public boolean stopRequested(Long jobId) { return stopRequests.contains(jobId); }

    public JobDto get(Long jobId) {
        TranslationJob j = find(jobId);
        List<String> lines = logs.findByJobIdOrderBySeqAsc(jobId).stream().map(JobLog::getLine).toList();
        return JobDto.of(j, lines);
    }

    public Optional<JobDto> latest(Long projectId, TranslationJob.Kind kind) {
        return jobs.findFirstByProjectIdAndKindOrderByIdDesc(projectId, kind).map(j -> get(j.getId()));
    }

    /** 프로젝트에서 지금 돌고 있는 작업(종류 불문). 화면을 새로고침해도 진행 중 작업에 다시 붙기 위해 쓴다. */
    public Optional<JobDto> running(Long projectId) {
        return jobs.findFirstByProjectIdAndStatus(projectId, TranslationJob.Status.RUNNING).map(j -> get(j.getId()));
    }

    /** 게임에서 지금 돌고 있는 작업. 화면을 새로고침해도 진행 중 작업에 다시 붙기 위해 쓴다. */
    public Optional<JobDto> runningForGame(Long gameId) {
        return jobs.findFirstByGameIdAndStatus(gameId, TranslationJob.Status.RUNNING).map(j -> get(j.getId()));
    }

    /** 프로젝트의 모든 작업 활동을 한 스트림으로. 처음에 최근 작업 스냅샷을 보내고, 이후 activity 이벤트를 계속 보낸다. */
    public SseEmitter projectEvents(Long projectId) {
        SseEmitter emitter = bus.subscribeProject(projectId);
        List<JobDto> recent = jobs.findTop5ByProjectIdOrderByIdDesc(projectId).stream()
                .map(j -> { JobDto d = get(j.getId()); List<String> tail = d.logs().size() > 30 ? d.logs().subList(d.logs().size() - 30, d.logs().size()) : d.logs();
                            return new JobDto(d.id(), d.projectId(), d.kind(), d.status(), d.totalBatches(), d.doneBatches(), tail); })
                .toList();
        try { emitter.send(SseEmitter.event().name("snapshot").data(new ActivitySnapshot(recent))); }
        catch (IOException e) { emitter.completeWithError(e); }
        return emitter;
    }

    public SseEmitter events(Long jobId) {
        SseEmitter emitter = bus.subscribe(jobId);
        JobDto snapshot = get(jobId);
        try { emitter.send(SseEmitter.event().name("snapshot").data(snapshot)); }
        catch (IOException e) { emitter.completeWithError(e); return emitter; }
        if (!"RUNNING".equals(snapshot.status())) emitter.complete();
        return emitter;
    }

    /** 서버 재시작 후 남은 RUNNING 작업은 STOPPED로 표시한다. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void markStaleRunningJobsStopped() {
        for (TranslationJob j : jobs.findByStatus(TranslationJob.Status.RUNNING)) {
            j.setStatus(TranslationJob.Status.STOPPED);
            j.setFinishedAt(Instant.now());
        }
    }

    private TranslationJob find(Long jobId) {
        return jobs.findById(jobId).orElseThrow(() -> new NotFoundException("작업을 찾을 수 없습니다: " + jobId));
    }
}
