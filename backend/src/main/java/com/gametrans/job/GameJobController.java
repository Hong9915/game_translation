package com.gametrans.job;

import com.gametrans.job.JobDtos.JobDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games/{id}/jobs")
public class GameJobController {
    private final JobService jobs;

    public GameJobController(JobService jobs) { this.jobs = jobs; }

    /** 게임에 진행 중인 작업이 있으면 200 + JobDto, 없으면 204. */
    @GetMapping("/running")
    public ResponseEntity<JobDto> running(@PathVariable Long id) {
        return jobs.runningForGame(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }
}
