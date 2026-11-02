package com.gametrans.workspace;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.job.JobEventBus;
import com.gametrans.review.ReviewEvents;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 게임 화면 실시간(설계 §3): 파일 이벤트가 게임 스트림에도 실리고, 게임 화면 접속자는 게임 스트림으로만 간다. */
class GameReviewEventsTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @SpyBean JobEventBus bus;

    @Test
    void fileEventsReachGameSubscribersAndGamePresenceStaysOnTheGameStream() throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE));
        long gameId = ((Number) JsonPath.read(mvc.perform(get("/api/projects/{id}", pid)).andReturn().getResponse().getContentAsString(), "$.gameId")).longValue();
        long sid = ((Number) JsonPath.read(mvc.perform(get("/api/games/{g}/review/rows", gameId)).andReturn().getResponse().getContentAsString(), "$.rows[0].id")).longValue();

        bus.subscribeGame(gameId);
        mvc.perform(put("/api/segments/{id}/translation", sid).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"정의의 판결\"}"))
                .andExpect(status().isOk());
        verify(bus).publishGameEvent(eq(gameId), eq(ReviewEvents.EVENT), argThat(p -> p instanceof ReviewEvents.SegmentUpdated));

        String name = "게임방-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        long rid = ((Number) JsonPath.read(mvc.perform(post("/api/reviewers").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        mvc.perform(post("/api/games/{g}/review/presence", gameId).header("X-Reviewer-Id", rid).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        verify(bus).publishGameEvent(eq(gameId), eq(ReviewEvents.EVENT), argThat(p -> p instanceof ReviewEvents.PresenceSnapshot));
        verify(bus, never()).publishProjectEvent(eq(pid), eq(ReviewEvents.EVENT), argThat(p -> p instanceof ReviewEvents.PresenceSnapshot));
        verify(bus, never()).publishProjectEvent(eq(-gameId), any(), any());
    }
}
