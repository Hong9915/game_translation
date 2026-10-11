package com.gametrans.workspace;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 행 하나의 변경 내역(2026-10-08): 리뷰 화면 우클릭 "변경 내역 보기"가 연다. 최신 판부터, 검토자 색과 작업 종류를 붙인다. */
class RowHistoryTest extends IntegrationTest {
    @Autowired MockMvc mvc;

    private long normalized() throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE))
                .andExpect(status().isOk());
        return pid;
    }

    private long rowId(long pid) throws Exception {
        String body = mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill")).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.parse(body).read("$.rows[0].id")).longValue();
    }

    private String reviewer() throws Exception {
        return mvc.perform(post("/api/reviewers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"지민-" + UUID.randomUUID().toString().substring(0, 6) + "\"}")).andReturn().getResponse().getContentAsString();
    }

    @Test
    void listsRevisionsNewestFirstWithReviewerAndActivityKind() throws Exception {
        long pid = normalized();
        long sid = rowId(pid);
        String who = reviewer();
        long rid = ((Number) JsonPath.parse(who).read("$.id")).longValue();
        String color = JsonPath.parse(who).read("$.color");
        mvc.perform(put("/api/segments/{id}/translation", sid).header("X-Reviewer-Id", rid)
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"정의의 재판\"}")).andExpect(status().isOk());

        mvc.perform(get("/api/review/{pid}/rows/{sid}/history", pid, sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].text").value("정의의 재판"))
                .andExpect(jsonPath("$[0].author").value("HUMAN"))
                .andExpect(jsonPath("$[0].reviewer.id").value(rid))
                .andExpect(jsonPath("$[0].reviewer.color").value(color))
                .andExpect(jsonPath("$[0].activityKind").value("EDIT"))
                .andExpect(jsonPath("$[1].text").value("정의의 심판"))
                .andExpect(jsonPath("$[1].author").value("EXISTING"))
                .andExpect(jsonPath("$[1].reviewer").doesNotExist())
                .andExpect(jsonPath("$[1].activityKind").doesNotExist());
    }

    @Test
    void rowOfAnotherProjectIsNotFound() throws Exception {
        long pid = normalized();
        long other = normalized();
        mvc.perform(get("/api/review/{pid}/rows/{sid}/history", other, rowId(pid))).andExpect(status().isNotFound());
    }
}
