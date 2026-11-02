package com.gametrans.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 게임 전체 리뷰(설계 2026-10-12): 한 게임의 두 파일 — MultiSheet(Items 3행·Skills 2행)와 기존 한국어 파일(Skill 4행).
 * 범위는 설정 시트 id 목록(sheets=), 비우면 게임 전체.
 */
class GameReviewTest extends IntegrationTest {
    @Autowired MockMvc mvc;

    record Fixture(long gameId, long multi, long existing, long items, long skills, long skill) {}

    Fixture fixture() throws Exception {
        String game = TestProjects.freshGame();
        long multi = TestProjects.uploadTwoSheets(mvc, game);
        normalize(multi, TestProjects.sheetNormalize("Items"));
        normalize(multi, TestProjects.sheetNormalize("Skills"));
        long existing = TestProjects.uploadExistingKo(mvc, game);
        normalize(existing, TestProjects.EXISTING_KO_NORMALIZE);
        long gameId = ((Number) JsonPath.read(mvc.perform(get("/api/projects/{id}", multi)).andReturn().getResponse().getContentAsString(), "$.gameId")).longValue();
        String summary = mvc.perform(get("/api/games/{g}/review/rows/summary", gameId)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(summary, "$.sheets[*].configId");
        return new Fixture(gameId, multi, existing, ids.get(0).longValue(), ids.get(1).longValue(), ids.get(2).longValue());
    }

    void normalize(long pid, String body) throws Exception {
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    @Test
    void summaryListsEverySheetOfTheGameInUploadThenConfigOrder() throws Exception {
        Fixture f = fixture();
        mvc.perform(get("/api/games/{g}/review/rows/summary", f.gameId()))
                .andExpect(jsonPath("$.sheets.length()").value(3))
                .andExpect(jsonPath("$.sheets[0].sheet").value("Items"))
                .andExpect(jsonPath("$.sheets[0].projectId").value(f.multi()))
                .andExpect(jsonPath("$.sheets[0].fileName").value("MultiSheet.xlsx"))
                .andExpect(jsonPath("$.sheets[1].sheet").value("Skills"))
                .andExpect(jsonPath("$.sheets[2].sheet").value("Skill"))
                .andExpect(jsonPath("$.sheets[2].projectId").value(f.existing()))
                .andExpect(jsonPath("$.total").value(9));
        // 범위를 주면 집계는 그 범위만, 시트 목록은 그대로 게임 전체
        mvc.perform(get("/api/games/{g}/review/rows/summary", f.gameId()).param("sheets", f.items() + "," + f.skill()))
                .andExpect(jsonPath("$.sheets.length()").value(3))
                .andExpect(jsonPath("$.total").value(7));
    }

    @Test
    void rowsSpanFilesAndSheetsInScopeOrder() throws Exception {
        Fixture f = fixture();
        mvc.perform(get("/api/games/{g}/review/rows", f.gameId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(9))
                .andExpect(jsonPath("$.rows[0].sheet").value("Items"))
                .andExpect(jsonPath("$.rows[0].projectId").value(f.multi()))
                .andExpect(jsonPath("$.rows[0].rowNo").value(2))
                .andExpect(jsonPath("$.rows[3].sheet").value("Skills"))
                .andExpect(jsonPath("$.rows[5].sheet").value("Skill"))
                .andExpect(jsonPath("$.rows[5].projectId").value(f.existing()));
        // 고른 순서가 아니라 게임 순서(파일 → 설정)로 나온다
        mvc.perform(get("/api/games/{g}/review/rows", f.gameId()).param("sheets", f.skill() + "," + f.items()))
                .andExpect(jsonPath("$.total").value(7))
                .andExpect(jsonPath("$.rows[0].sheet").value("Items"))
                .andExpect(jsonPath("$.rows[3].sheet").value("Skill"));
        // 쪽 나누기는 범위 전체에 걸친다
        mvc.perform(get("/api/games/{g}/review/rows", f.gameId()).param("page", "1").param("size", "4"))
                .andExpect(jsonPath("$.rows.length()").value(4))
                .andExpect(jsonPath("$.rows[0].sheet").value("Skills"))
                .andExpect(jsonPath("$.rows[1].sheet").value("Skill"));
    }

    @Test
    void singleSheetScopeMatchesTheFileScreen() throws Exception {
        Fixture f = fixture();
        String file = mvc.perform(get("/api/review/{id}/rows", f.existing()).param("sheet", "Skill")).andReturn().getResponse().getContentAsString();
        String game = mvc.perform(get("/api/games/{g}/review/rows", f.gameId()).param("sheets", String.valueOf(f.skill()))).andReturn().getResponse().getContentAsString();
        List<Object> a = JsonPath.read(file, "$.rows[*].id"), b = JsonPath.read(game, "$.rows[*].id");
        assertThat(b).isEqualTo(a);
        assertThat((Integer) JsonPath.read(file, "$.total")).isEqualTo((Integer) JsonPath.read(game, "$.total"));
    }

    @Test
    void searchAndDuplicateFilterWorkAcrossTheScope() throws Exception {
        Fixture f = fixture();
        // 霜语者는 Items!3과 Skills!3 두 곳. 한쪽을 확정해 전파를 막고 다른 쪽만 고쳐 일부러 갈리게 한다.
        String pair = f.items() + "," + f.skills();
        String body = mvc.perform(get("/api/games/{g}/review/rows", f.gameId()).param("q", "霜语者").param("sheets", pair)).andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(body, "$.rows[*].id");
        assertThat(ids).hasSize(2);
        mvc.perform(put("/api/segments/{id}/marks", ids.get(1).longValue()).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/segments/{id}/translation", ids.get(0).longValue()).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"서리\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/games/{g}/review/rows", f.gameId()).param("filter", "DUP_CONFLICT").param("q", "霜语者").param("sheets", pair))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.rows[0].sheet").value("Items"))
                .andExpect(jsonPath("$.rows[1].sheet").value("Skills"));
        // 범위를 한 시트로 좁히면 그 시트 행만(正义裁决도 기존 파일 번역과 갈려 있어 검색으로 霜语者만 본다)
        mvc.perform(get("/api/games/{g}/review/rows", f.gameId()).param("filter", "DUP_CONFLICT").param("q", "霜语者").param("sheets", String.valueOf(f.skills())))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].sheet").value("Skills"));
    }

    @Test
    void sheetOfAnotherGameIsRejected() throws Exception {
        Fixture f = fixture();
        Fixture other = fixture();
        mvc.perform(get("/api/games/{g}/review/rows", f.gameId()).param("sheets", String.valueOf(other.items())))
                .andExpect(status().isBadRequest());
    }
}
