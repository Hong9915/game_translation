package com.gametrans.glossary;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.job.JobEventBus;
import com.gametrans.project.ProjectRepository;
import com.gametrans.review.ReviewEvents;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 단어장 항목이 바뀌면 그 게임의 파일을 보고 있는 사람들에게 목록을 다시 읽으라고 알린다. */
class GlossaryEventsTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @SpyBean JobEventBus bus;

    private long project() throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));
        return pid;
    }

    private Long gameOf(long pid) { return projects.findById(pid).orElseThrow().getGame().getId(); }

    private void verifyGlossaryEvent(long pid) {
        verify(bus).publishProjectEvent(eq(pid), eq(ReviewEvents.EVENT),
                argThat(p -> p instanceof ReviewEvents.GlossaryChanged c && c.type().equals("glossary")));
    }

    private long add(Long gameId, String find, String ko) throws Exception {
        String saved = mvc.perform(post("/api/games/{id}/glossary", gameId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"SRC\",\"matchMode\":\"PARTIAL\",\"find\":\"" + find + "\",\"ko\":\"" + ko + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(saved).read("$.entry.id", Long.class);
    }

    @Test
    void addingAnEntryThatChangesNoRowStillNotifiesTheGame() throws Exception {
        long pid = project();
        clearInvocations(bus);
        add(gameOf(pid), "没有这个词" + System.nanoTime(), "없는 말");
        verifyGlossaryEvent(pid);
    }

    @Test
    void editingDeletingAndSharingNotify() throws Exception {
        long pid = project();
        String find = "活动期间" + System.nanoTime();
        long id = add(gameOf(pid), find, "이벤트 기간 동");

        clearInvocations(bus);
        mvc.perform(put("/api/glossary/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"SRC\",\"matchMode\":\"PARTIAL\",\"find\":\"" + find + "\",\"ko\":\"이벤트 기간 동안\"}"));
        verifyGlossaryEvent(pid);

        clearInvocations(bus);
        mvc.perform(post("/api/glossary/{id}/share", id));
        verifyGlossaryEvent(pid);   // 공유 단어집은 모든 게임에 걸리므로 이 게임에도 간다

        clearInvocations(bus);
        mvc.perform(delete("/api/glossary/{id}", id));
        verifyGlossaryEvent(pid);
    }
}
