package com.gametrans.review;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** "최종 수정" 표시(설계 2026-10-17 C): 단건·일괄 체크, 필터, 요약 수치. finalReview() 워크북은 검수 대상 5행. */
class FinalMarkTest extends IntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void finalMarkTogglesFiltersAndCounts() throws Exception {
        long pid = TestProjects.uploadFinalReview(mvc);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.FINAL_REVIEW_NORMALIZE))
                .andExpect(status().isOk());
        String list = mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Sheet1")).andReturn().getResponse().getContentAsString();
        long sid = ((Number) JsonPath.parse(list).read("$.rows[1].id")).longValue();
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Sheet1"))
                .andExpect(jsonPath("$.rows[0].finalReview").value(true))
                .andExpect(jsonPath("$.rows[0].finalMark").value(false));

        mvc.perform(put("/api/segments/{id}/marks", sid).contentType(MediaType.APPLICATION_JSON).content("{\"finalMark\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.finalMark").value(true));
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Sheet1").param("filter", "FINAL_MARK")).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Sheet1").param("filter", "NOT_FINAL_MARK")).andExpect(jsonPath("$.total").value(4));
        mvc.perform(get("/api/review/{id}/rows/summary", pid).param("sheet", "Sheet1")).andExpect(jsonPath("$.finalMark").value(1));

        mvc.perform(post("/api/review/{id}/marks", pid).contentType(MediaType.APPLICATION_JSON).content("{\"sheet\":\"Sheet1\",\"finalMark\":true}"))
                .andExpect(jsonPath("$.updated").value(5));
        mvc.perform(get("/api/review/{id}/rows/summary", pid).param("sheet", "Sheet1")).andExpect(jsonPath("$.finalMark").value(5));
    }
}
