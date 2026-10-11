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

class WorkspaceSummaryTest extends IntegrationTest {
    @Autowired MockMvc mvc;

    private long reviewer() throws Exception {
        String body = mvc.perform(post("/api/reviewers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"수현-" + UUID.randomUUID().toString().substring(0, 6) + "\"}")).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.parse(body).read("$.id")).longValue();
    }

    @Test
    void countsChangedAndMarksPerSheetAndPerReviewer() throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE))
                .andExpect(status().isOk());
        String rows = mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill")).andReturn().getResponse().getContentAsString();
        long first = ((Number) JsonPath.parse(rows).read("$.rows[0].id")).longValue();
        long second = ((Number) JsonPath.parse(rows).read("$.rows[1].id")).longValue();
        long suhyun = reviewer();

        mvc.perform(get("/api/review/{id}/rows/summary", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sheets.length()").value(1))
                .andExpect(jsonPath("$.sheets[0].sheet").value("Skill"))
                .andExpect(jsonPath("$.sheets[0].total").value(4))
                .andExpect(jsonPath("$.sheets[0].changed").value(0))
                .andExpect(jsonPath("$.sheets[0].review1").value(0))
                .andExpect(jsonPath("$.sheets[0].review2").value(0))
                .andExpect(jsonPath("$.sheets[0].needsCheck").value(0))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.unassigned").value(4))
                .andExpect(jsonPath("$.reviewers.length()").value(0));

        mvc.perform(put("/api/segments/{id}/translation", first).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"정의의 판결\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/projects/{id}/assign", pid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"segmentIds\":[" + first + "," + second + "],\"assigneeId\":" + suhyun + "}")).andExpect(status().isOk());
        // 2행: 1차만, 1행: 2차·확인 필요·확정
        mvc.perform(put("/api/segments/{id}/marks", second).header("X-Reviewer-Id", suhyun)
                .contentType(MediaType.APPLICATION_JSON).content("{\"review1\":true}")).andExpect(status().isOk());
        mvc.perform(put("/api/segments/{id}/marks", first).contentType(MediaType.APPLICATION_JSON)
                .content("{\"review2\":true,\"needsCheck\":true,\"confirmed\":true}")).andExpect(status().isOk());

        mvc.perform(get("/api/review/{id}/rows/summary", pid).param("sheet", "Skill"))
                .andExpect(jsonPath("$.sheets[0].changed").value(1))
                .andExpect(jsonPath("$.sheets[0].review1").value(1))
                .andExpect(jsonPath("$.sheets[0].review2").value(1))
                .andExpect(jsonPath("$.sheets[0].needsCheck").value(1))
                .andExpect(jsonPath("$.sheets[0].confirmed").value(1))
                .andExpect(jsonPath("$.changed").value(1))
                .andExpect(jsonPath("$.review1").value(1))
                .andExpect(jsonPath("$.review2").value(1))
                .andExpect(jsonPath("$.needsCheck").value(1))
                .andExpect(jsonPath("$.confirmed").value(1))
                .andExpect(jsonPath("$.unassigned").value(2))
                .andExpect(jsonPath("$.reviewers[0].reviewer.id").value(suhyun))
                .andExpect(jsonPath("$.reviewers[0].assigned").value(2))
                .andExpect(jsonPath("$.reviewers[0].done").value(1));   // 담당 행 중 2차 체크 수
    }

    @Test
    void sheetsFollowNormalizeOrderNotAlphabetical() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());
        // Skills를 먼저 설정하면 탭도 Skills가 먼저다 — 검수자가 정한 순서를 지킨다
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.sheetNormalize("Skills"))).andExpect(status().isOk());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.sheetNormalize("Items"))).andExpect(status().isOk());
        mvc.perform(get("/api/review/{id}/rows/summary", pid))
                .andExpect(jsonPath("$.sheets[0].sheet").value("Skills"))
                .andExpect(jsonPath("$.sheets[1].sheet").value("Items"))
                // NEW 열 모드라 기존 한국어도 Fix도 없다 → 수정됨 0
                .andExpect(jsonPath("$.sheets[0].changed").value(0));
    }
}
