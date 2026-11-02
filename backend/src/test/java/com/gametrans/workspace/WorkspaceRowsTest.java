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

/**
 * 검수 워크스페이스 목록. withExistingKo() 워크북(Skill 시트): 2행 기존 한국어 있음, 3행 의도적 N/A(건너뜀),
 * 4행 KR N/A, 5행 빈칸, 6행 원문 none(건너뜀), 7행 KR None → 검수 대상 4행(2·4·5·7).
 */
class WorkspaceRowsTest extends IntegrationTest {
    @Autowired MockMvc mvc;

    private long normalized() throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE))
                .andExpect(status().isOk());
        return pid;
    }

    private long rowId(long pid, int index) throws Exception {
        String body = mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill")).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.parse(body).read("$.rows[" + index + "].id")).longValue();
    }

    private long reviewer() throws Exception {
        String body = mvc.perform(post("/api/reviewers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"지민-" + UUID.randomUUID().toString().substring(0, 6) + "\"}")).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.parse(body).read("$.id")).longValue();
    }

    @Test
    void listsTheSheetInRowOrderWithFixSeededFromExistingKo() throws Exception {
        long pid = normalized();
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(500))
                .andExpect(jsonPath("$.rows[0].rowNo").value(2))
                .andExpect(jsonPath("$.rows[0].keyText").value("211011101"))
                .andExpect(jsonPath("$.rows[0].src").value("正义裁决"))
                .andExpect(jsonPath("$.rows[0].en").value("Rule of Law"))
                .andExpect(jsonPath("$.rows[0].existingKo").value("정의의 심판"))
                .andExpect(jsonPath("$.rows[0].fix").value("정의의 심판"))
                .andExpect(jsonPath("$.rows[0].changed").value(false))
                .andExpect(jsonPath("$.rows[0].review1").value(false))
                .andExpect(jsonPath("$.rows[0].history[0].author").value("EXISTING"))
                .andExpect(jsonPath("$.rows[1].rowNo").value(4))
                .andExpect(jsonPath("$.rows[1].existingKo").value("N/A"))
                .andExpect(jsonPath("$.rows[1].fix").doesNotExist())
                .andExpect(jsonPath("$.rows[2].rowNo").value(5))
                .andExpect(jsonPath("$.rows[3].rowNo").value(7));
    }

    @Test
    void changedAndEmptyFixFilters() throws Exception {
        long pid = normalized();
        long sid = rowId(pid, 0);
        mvc.perform(put("/api/segments/{id}/translation", sid).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"정의의 판결\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("filter", "CHANGED"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].id").value(sid))
                .andExpect(jsonPath("$.rows[0].fix").value("정의의 판결"))
                .andExpect(jsonPath("$.rows[0].changed").value(true))
                .andExpect(jsonPath("$.rows[0].lastEditor").doesNotExist());
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("filter", "EMPTY_FIX"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.rows[0].rowNo").value(4));
    }

    @Test
    void searchMatchesSourceAndFixButNotId() throws Exception {
        long pid = normalized();
        // ID는 찾지 않는다 — "level"로 찾으면 LEVEL_… 키 행이 쏟아져 Fix의 Level을 가렸다(2026-10-26)
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("q", "211011102"))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("q", "正义"))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.rows[0].rowNo").value(2));
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("q", "심판"))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.rows[0].rowNo").value(2));
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("q", "없는말"))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void searchInExistingKoLooksOnlyAtExistingKo() throws Exception {
        long pid = normalized();
        mvc.perform(put("/api/segments/{id}/translation", rowId(pid, 0)).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"정의의 판결\"}"))
                .andExpect(status().isOk());

        // Fix가 바뀌어 기본 검색에선 안 걸리는 옛 표기도 기존 한국어에서 찾으면 걸린다
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("q", "심판"))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("q", "심판").param("qIn", "EXISTING_KO"))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.rows[0].rowNo").value(2));
        // 기존 한국어만 보므로 Fix·원문·ID에만 있는 말은 안 걸린다
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("q", "판결").param("qIn", "EXISTING_KO"))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("q", "正义").param("qIn", "EXISTING_KO"))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void mineAndUnassignedFilters() throws Exception {
        long pid = normalized();
        long jimin = reviewer();
        long sid = rowId(pid, 0);
        mvc.perform(post("/api/projects/{id}/assign", pid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"segmentIds\":[" + sid + "],\"assigneeId\":" + jimin + "}")).andExpect(status().isOk());
        mvc.perform(put("/api/segments/{id}/marks", sid).header("X-Reviewer-Id", jimin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"review1\":true}")).andExpect(status().isOk());

        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("filter", "MINE").header("X-Reviewer-Id", jimin))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].assignee.id").value(jimin))
                .andExpect(jsonPath("$.rows[0].review1").value(true));
        // 익명이면 "내 담당"은 아무것도 아니다
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("filter", "MINE"))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("filter", "UNASSIGNED"))
                .andExpect(jsonPath("$.total").value(3));
        // 1차만 켰다(2차는 따로). 확인 필요는 다른 행 하나에만.
        long other = rowId(pid, 1);
        mvc.perform(put("/api/segments/{id}/marks", other).contentType(MediaType.APPLICATION_JSON).content("{\"needsCheck\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("filter", "NOT_REVIEW1"))
                .andExpect(jsonPath("$.total").value(3));
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("filter", "NOT_REVIEW2"))
                .andExpect(jsonPath("$.total").value(4));
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("filter", "NEEDS_CHECK"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].id").value(other));
        // 확정은 1·2차와 따로: 확정한 행 하나만 "확정 안 함"에서 빠진다
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("filter", "NOT_CONFIRMED"))
                .andExpect(jsonPath("$.total").value(4));
        mvc.perform(put("/api/segments/{id}/marks", other).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("filter", "NOT_CONFIRMED"))
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void pagesInsideTheSheetAndRequiresASheet() throws Exception {
        long pid = normalized();
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("page", "1").param("size", "2"))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].rowNo").value(5))
                .andExpect(jsonPath("$.rows[1].rowNo").value(7));
        mvc.perform(get("/api/review/{id}/rows", pid)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "없는시트"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }
}
