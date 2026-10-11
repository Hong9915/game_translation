package com.gametrans.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class JobServiceTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JobService jobs;
    @Autowired JobRunner runner;
    @Autowired JobEventBus bus;

    @Test
    void runnerLogsProgressAndFinishesDone() throws Exception {
        long pid = TestProjects.upload(mvc);
        TranslationJob job = jobs.create(pid, TranslationJob.Kind.TRANSLATE);
        runner.run(job.getId(), ctx -> {
            ctx.progress(0, 2);
            ctx.log("묶음 1/2 번역 중…");
            ctx.progress(1, 2);
            ctx.log("묶음 2/2 번역 중…");
            ctx.progress(2, 2);
        });
        JobDtos.JobDto dto = jobs.get(job.getId());
        assertThat(dto.status()).isEqualTo("DONE");
        assertThat(dto.doneBatches()).isEqualTo(2);
        assertThat(dto.logs()).containsExactly("묶음 1/2 번역 중…", "묶음 2/2 번역 중…");

        mvc.perform(get("/api/jobs/{id}", job.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.logs.length()").value(2));
    }

    @Test
    void runningEndpointReturnsTheInFlightJobThenNoContent() throws Exception {
        long pid = TestProjects.upload(mvc);
        mvc.perform(get("/api/projects/{id}/jobs/running", pid)).andExpect(status().isNoContent());
        TranslationJob job = jobs.create(pid, TranslationJob.Kind.EXTRACT_TERMS);
        mvc.perform(get("/api/projects/{id}/jobs/running", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(job.getId()))
                .andExpect(jsonPath("$.kind").value("EXTRACT_TERMS"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
        jobs.finish(job.getId(), TranslationJob.Status.DONE);
        mvc.perform(get("/api/projects/{id}/jobs/running", pid)).andExpect(status().isNoContent());
    }

    @Test
    void runnerMarksFailedOnException() throws Exception {
        long pid = TestProjects.upload(mvc);
        TranslationJob job = jobs.create(pid, TranslationJob.Kind.SHORTEN);
        runner.run(job.getId(), ctx -> { throw new IllegalStateException("boom"); });
        JobDtos.JobDto dto = jobs.get(job.getId());
        assertThat(dto.status()).isEqualTo("FAILED");
        assertThat(dto.logs()).anyMatch(l -> l.contains("boom"));
    }

    @Test
    void stopRequestMakesJobStopped() throws Exception {
        long pid = TestProjects.upload(mvc);
        TranslationJob job = jobs.create(pid, TranslationJob.Kind.TRANSLATE);
        mvc.perform(post("/api/jobs/{id}/stop", job.getId())).andExpect(status().isOk());
        runner.run(job.getId(), ctx -> {
            if (ctx.stopRequested()) { ctx.log("중지됨"); return; }
            ctx.log("should not run");
        });
        assertThat(jobs.get(job.getId()).status()).isEqualTo("STOPPED");
    }

    @Test
    void secondRunningJobForSameProjectIsRejected() throws Exception {
        long pid = TestProjects.upload(mvc);
        jobs.create(pid, TranslationJob.Kind.TRANSLATE);
        assertThatThrownBy(() -> jobs.create(pid, TranslationJob.Kind.TRANSLATE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void projectStreamCarriesEveryJobsActivity() throws Exception {
        long pid = TestProjects.upload(mvc);
        TranslationJob old = jobs.create(pid, TranslationJob.Kind.EXTRACT_TERMS);
        runner.run(old.getId(), ctx -> ctx.log("old extract line"));
        MvcResult r = mvc.perform(get("/api/projects/{id}/jobs/events", pid))
                .andExpect(request().asyncStarted()).andReturn();

        TranslationJob job = jobs.create(pid, TranslationJob.Kind.TRANSLATE);
        jobs.log(job.getId(), "translate live line");
        jobs.progress(job.getId(), 1, 2);
        jobs.finish(job.getId(), TranslationJob.Status.DONE);
        bus.completeProject(pid);  // 프로젝트 스트림은 원래 닫히지 않으므로 테스트에서 직접 닫는다

        String body = mvc.perform(asyncDispatch(r)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("event:snapshot").contains("old extract line")
                .contains("event:activity")
                .contains("\"type\":\"started\"").contains("\"kind\":\"TRANSLATE\"")
                .contains("translate live line")
                .contains("\"type\":\"progress\"")
                .contains("\"type\":\"done\"");
    }

    @Test
    void eventsStreamSendsSnapshotForFinishedJob() throws Exception {
        long pid = TestProjects.upload(mvc);
        TranslationJob job = jobs.create(pid, TranslationJob.Kind.TRANSLATE);
        runner.run(job.getId(), ctx -> ctx.log("done line"));
        MvcResult r = mvc.perform(get("/api/jobs/{id}/events", job.getId()))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(r))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:snapshot")))
                .andExpect(content().string(containsString("done line")));
    }

    @Test
    void eventsStreamDeliversLiveLogProgressAndDoneEvents() throws Exception {
        long pid = TestProjects.upload(mvc);
        TranslationJob job = jobs.create(pid, TranslationJob.Kind.TRANSLATE);
        MvcResult r = mvc.perform(get("/api/jobs/{id}/events", job.getId()))
                .andExpect(request().asyncStarted()).andReturn();

        jobs.log(job.getId(), "live line");
        jobs.progress(job.getId(), 1, 2);
        jobs.finish(job.getId(), TranslationJob.Status.DONE);

        mvc.perform(asyncDispatch(r))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:snapshot")))
                .andExpect(content().string(containsString("event:log")))
                .andExpect(content().string(containsString("live line")))
                .andExpect(content().string(containsString("event:progress")))
                .andExpect(content().string(containsString("event:done")));
    }
}
