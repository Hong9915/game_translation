package com.gametrans.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 최종 리뷰 레인(설계 2026-10-17): 한 게임에 일반 파일과 최종 리뷰 파일이 같은 원문(正义裁决)을 가질 때
 * 같은 원문 묶음 기능이 반대 레인을 건드리지 않는다. 일반 2행 "정의의 심판", 최종 2행 기존 "정의의 심판" → 수정 "정의의 판결".
 */
class FinalLaneTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;

    /** [일반, 최종] 프로젝트 id. */
    private long[] twoLanes() throws Exception {
        String game = TestProjects.freshGame();
        long normal = TestProjects.uploadExistingKo(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", normal).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE))
                .andExpect(status().isOk());
        long fin = TestProjects.uploadExistingKoAsFinal(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", fin).contentType(MediaType.APPLICATION_JSON).content(TestProjects.FINAL_SKILL_NORMALIZE))
                .andExpect(status().isOk());
        return new long[] {normal, fin};
    }

    private Segment row2(long pid) {
        return segments.findAll().stream().filter(s -> s.getProjectId() == pid && s.getRowNo() == 2).findFirst().orElseThrow();
    }

    @Test
    void duplicateCountOnlyCountsTheSameLane() throws Exception {
        long[] p = twoLanes();
        mvc.perform(get("/api/review/{id}/rows", p[1]).param("sheet", "Skill")).andExpect(jsonPath("$.rows[0].dupCount").value(0));
        mvc.perform(get("/api/review/{id}/rows", p[0]).param("sheet", "Skill")).andExpect(jsonPath("$.rows[0].dupCount").value(0));
    }

    @Test
    void sameSourceEditStaysInItsLane() throws Exception {
        long[] p = twoLanes();
        mvc.perform(put("/api/segments/{id}/translation", row2(p[1]).getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"정의의 철퇴\"}")).andExpect(status().isOk());
        assertThat(row2(p[0]).getLatestKo()).isEqualTo("정의의 심판");
    }

    @Test
    void dupConflictComparesOnlyWithinTheLane() throws Exception {
        long[] p = twoLanes();
        // 일반 "정의의 심판" vs 최종 "정의의 판결" — 레인이 달라 불일치가 아니다
        mvc.perform(get("/api/review/{id}/rows", p[1]).param("sheet", "Skill").param("filter", "DUP_CONFLICT"))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void normalizeInheritsOnlyFromTheSameLane() throws Exception {
        String game = TestProjects.freshGame();
        long fin = TestProjects.uploadExistingKoAsFinal(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", fin).contentType(MediaType.APPLICATION_JSON).content(TestProjects.FINAL_SKILL_NORMALIZE))
                .andExpect(status().isOk());
        // 일반 파일을 NEW 모드로(기존 한국어 안 읽음) — 최종 레인의 "정의의 판결"을 이어받으면 안 된다
        long normal = TestProjects.uploadExistingKo(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", normal).contentType(MediaType.APPLICATION_JSON).content("""
                {"sheetName":"Skill","headerRow":1,"keyCol":"A","pairs":[{"srcCol":"B","enCol":"C","koColMode":"NEW","koHeader":"KR_Fix"}]}
                """)).andExpect(status().isOk());
        assertThat(row2(normal).getLatestKo()).isNull();
    }

    @Test
    void gameScopeDefaultsToTheFinalLaneAndRejectsMixedSheets() throws Exception {
        long[] p = twoLanes();
        long gameId = ((Number) JsonPath.parse(mvc.perform(get("/api/projects/{id}", p[0])).andReturn().getResponse().getContentAsString())
                .read("$.gameId")).longValue();
        String sum = mvc.perform(get("/api/games/{g}/review/rows/summary", gameId)).andReturn().getResponse().getContentAsString();
        // 요약의 시트 목록은 두 레인 다(프론트가 전환을 그린다), 수치는 기본 레인(최종)만
        List<Boolean> lanes = JsonPath.parse(sum).read("$.sheets[*].finalReview");
        assertThat(lanes).containsExactlyInAnyOrder(false, true);
        mvc.perform(get("/api/games/{g}/review/rows", gameId)).andExpect(jsonPath("$.total").value(2));
        List<Integer> ids = JsonPath.parse(sum).read("$.sheets[*].configId");
        mvc.perform(get("/api/games/{g}/review/rows", gameId).param("sheets", ids.get(0) + "," + ids.get(1)))
                .andExpect(status().isBadRequest());
    }
}
