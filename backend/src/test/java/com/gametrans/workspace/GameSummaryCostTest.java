package com.gametrans.workspace;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.normalize.SegmentRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 최종 리뷰 I1: 게임 요약은 범위 밖 시트의 "수정됨"을 세지 않는다 — 게임 화면은 이벤트마다 요약을 다시 읽는다. */
class GameSummaryCostTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @SpyBean SegmentRepository segments;

    @Test
    void changedIsCountedOnlyForSheetsInScope() throws Exception {
        String game = TestProjects.freshGame();
        long multi = TestProjects.uploadTwoSheets(mvc, game);
        for (String s : List.of("Items", "Skills"))
            mvc.perform(put("/api/projects/{id}/normalize", multi).contentType(MediaType.APPLICATION_JSON).content(TestProjects.sheetNormalize(s)));
        long gameId = ((Number) JsonPath.read(mvc.perform(get("/api/projects/{id}", multi)).andReturn().getResponse().getContentAsString(), "$.gameId")).longValue();
        List<Number> ids = JsonPath.read(mvc.perform(get("/api/games/{g}/review/rows/summary", gameId)).andReturn().getResponse().getContentAsString(), "$.sheets[*].configId");

        clearInvocations(segments);
        mvc.perform(get("/api/games/{g}/review/rows/summary", gameId).param("sheets", String.valueOf(ids.get(0))))
                .andExpect(jsonPath("$.sheets.length()").value(2))
                .andExpect(jsonPath("$.total").value(3));
        verify(segments, times(1)).count(any(Specification.class));
    }
}
