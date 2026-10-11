package com.gametrans.job;

import com.gametrans.job.JobDtos.JobDto;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private final JobService jobs;

    public JobController(JobService jobs) { this.jobs = jobs; }

    @GetMapping("/{id}")
    public JobDto get(@PathVariable Long id) { return jobs.get(id); }

    @PostMapping("/{id}/stop")
    public JobDto stop(@PathVariable Long id) {
        jobs.requestStop(id);
        return jobs.get(id);
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long id) { return jobs.events(id); }
}
