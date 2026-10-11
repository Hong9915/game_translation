package com.gametrans.job;

import com.gametrans.job.JobDtos.JobDto;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{id}/jobs")
public class ProjectJobController {
    private final JobService jobs;

    public ProjectJobController(JobService jobs) { this.jobs = jobs; }

    /** 진행 중인 작업이 있으면 200 + JobDto, 없으면 204. */
    @GetMapping("/running")
    public ResponseEntity<JobDto> running(@PathVariable Long id) {
        return jobs.running(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** 프로젝트의 모든 GPT 작업 활동(시작·로그·진행·완료)을 SSE로. 화면 하단 활동 패널이 구독한다. */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long id) { return jobs.projectEvents(id); }
}
