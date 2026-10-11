package com.gametrans.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class PresenceControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;

    long reviewer(String prefix) throws Exception {
        String name = prefix + "-" + UUID.randomUUID().toString().substring(0, 6);
        String body = mvc.perform(post("/api/reviewers").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.parse(body).read("$.id")).longValue();
    }

    /** 실제 프로젝트일 필요는 없다. 다른 테스트와 겹치지 않도록 큰 값을 쓴다. */
    long freshProjectId() { return System.nanoTime(); }

    @Test
    void headerPresentAddsToSnapshotWithEditingSegment() throws Exception {
        long pid = freshProjectId();
        long jimin = reviewer("지민");

        String body = mvc.perform(post("/api/projects/{id}/presence", pid).header("X-Reviewer-Id", jimin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sheet\":\"Words\",\"editingSegmentId\":42}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(JsonPath.parse(body).read("$.reviewers[0].id", Number.class).longValue()).isEqualTo(jimin);
        assertThat(JsonPath.parse(body).read("$.reviewers[0].name", String.class)).startsWith("지민-");
        assertThat(JsonPath.parse(body).read("$.reviewers[0].editingSegmentId", Number.class).longValue()).isEqualTo(42L);
    }

    @Test
    void missingHeaderIsAnonymousAndNotAdded() throws Exception {
        long pid = freshProjectId();

        mvc.perform(post("/api/projects/{id}/presence", pid)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sheet\":\"Words\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewers").isEmpty());
    }

    @Test
    void unknownReviewerIdIsRejected() throws Exception {
        long pid = freshProjectId();

        mvc.perform(post("/api/projects/{id}/presence", pid).header("X-Reviewer-Id", 999999999L)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sheet\":\"Words\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteRemovesReviewerFromSnapshot() throws Exception {
        long pid = freshProjectId();
        long jimin = reviewer("지민");

        mvc.perform(post("/api/projects/{id}/presence", pid).header("X-Reviewer-Id", jimin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sheet\":\"Words\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewers.length()").value(1));

        mvc.perform(delete("/api/projects/{id}/presence", pid).header("X-Reviewer-Id", jimin))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/projects/{id}/presence", pid)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewers").isEmpty());
    }
}
