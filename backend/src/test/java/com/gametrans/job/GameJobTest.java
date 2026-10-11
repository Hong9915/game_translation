package com.gametrans.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 프로젝트 없이 게임에 붙는 작업: 게임당 하나만 돌고, 화면이 다시 붙을 수 있게 진행 중 작업을 알려 준다. */
class GameJobTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JobService jobs;

    private long newGame() throws Exception {
        String body = mvc.perform(post("/api/games").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"게임 작업 " + System.nanoTime() + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body).read("$.id", Long.class);
    }

    @Test
    void oneRunningJobPerGameAndTheRunningEndpointFindsIt() throws Exception {
        long gid = newGame();
        mvc.perform(get("/api/games/{id}/jobs/running", gid)).andExpect(status().isNoContent());

        TranslationJob job = jobs.createForGame(gid, TranslationJob.Kind.COLLAPSE_TERMS);
        assertThat(job.getProjectId()).isNull();
        assertThat(job.getGameId()).isEqualTo(gid);
        jobs.log(job.getId(), "첫 줄");      // 프로젝트 스트림이 없어도 로그·진행·완료가 터지지 않아야 한다
        jobs.progress(job.getId(), 1, 2);
        mvc.perform(get("/api/games/{id}/jobs/running", gid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(job.getId()))
                .andExpect(jsonPath("$.kind").value("COLLAPSE_TERMS"))
                .andExpect(jsonPath("$.projectId").doesNotExist())
                .andExpect(jsonPath("$.logs[0]").value("첫 줄"));

        assertThatThrownBy(() -> jobs.createForGame(gid, TranslationJob.Kind.COLLAPSE_TERMS))
                .isInstanceOf(IllegalStateException.class);

        jobs.finish(job.getId(), TranslationJob.Status.DONE);
        mvc.perform(get("/api/games/{id}/jobs/running", gid)).andExpect(status().isNoContent());
        mvc.perform(get("/api/jobs/{id}", job.getId())).andExpect(jsonPath("$.status").value("DONE"));
    }
}
