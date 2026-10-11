package com.gametrans.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** segment.latest_ko는 DB 트리거가 유지한다 — 리비전을 어느 경로로 넣든 최신 판이 캐시에 보여야 한다. */
class LatestKoCacheTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;

    private long normalizedExistingKo() throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE))
                .andExpect(status().isOk());
        return pid;
    }

    private Segment row(long pid, int rowNo) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getRowNo() == rowNo).findFirst().orElseThrow();
    }

    @Test
    void seededExistingKoIsCachedAndHumanEditReplacesIt() throws Exception {
        long pid = normalizedExistingKo();
        // 2행은 기존 한국어가 0차 리비전으로 들어갔고, 4행(N/A)은 리비전이 없다
        assertThat(row(pid, 2).getLatestKo()).isEqualTo("정의의 심판");
        assertThat(row(pid, 4).getLatestKo()).isNull();

        String list = mvc.perform(get("/api/projects/{id}/segments", pid)).andReturn().getResponse().getContentAsString();
        long sid = ((Number) JsonPath.parse(list).read("$.rows[0].id")).longValue();
        mvc.perform(put("/api/segments/{id}/translation", sid).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"정의의 판결\"}"))
                .andExpect(status().isOk());

        assertThat(row(pid, 2).getLatestKo()).isEqualTo("정의의 판결");
    }

    @Test
    void renormalizingASheetRebuildsTheCache() throws Exception {
        long pid = normalizedExistingKo();
        // 시트를 다시 정규화하면 세그먼트·리비전이 지워졌다 다시 만들어진다 — 삭제 트리거가 에러 없이 지나가고 캐시도 다시 찬다
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE))
                .andExpect(status().isOk());
        assertThat(row(pid, 2).getLatestKo()).isEqualTo("정의의 심판");
    }
}
