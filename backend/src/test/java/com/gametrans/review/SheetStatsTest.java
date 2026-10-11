package com.gametrans.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.llm.LlmClient;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 검토 목록의 시트별 집계(탭용): 필터와 무관하게 프로젝트 전체 행 기준, 시트 이름순. */
class SheetStatsTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;

    @BeforeEach
    void setUp() {
        FakeLlmClient fake = (FakeLlmClient) llm;
        fake.translations.clear();
        fake.translations.put("烈焰之剑", "화염검");
        fake.translations.put("霜语者", "서리");
        // 원문 4자, 번역 6자: ratio 1.0에서는 flagged, ratio 2.0에서는 아니다.
        fake.translations.put("破晓之盾", "새벽의방패다");
        fake.translations.put("正义裁决", "정의판결");
        fake.translations.put("破魔一击", "마귀타파");
    }

    private long twoSheetProject() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                        .content(TestProjects.sheetNormalize("Items")))
                .andExpect(status().isOk());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                        .content(TestProjects.sheetNormalize("Skills")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"))
                .andExpect(status().isOk());
        return pid;
    }

    @Test
    void sheetFilterOnlyAffectsRowsWhileSheetsListsBothWithFullCounts() throws Exception {
        long pid = twoSheetProject();

        String body = mvc.perform(get("/api/projects/{id}/segments", pid).param("sheet", "Items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.rows.length()").value(3))
                .andExpect(jsonPath("$.sheets.length()").value(2))
                .andExpect(jsonPath("$.sheets[0].sheet").value("Items"))
                .andExpect(jsonPath("$.sheets[0].total").value(3))
                .andExpect(jsonPath("$.sheets[0].flagged").value(1))
                .andExpect(jsonPath("$.sheets[0].termMiss").value(0))
                .andExpect(jsonPath("$.sheets[0].done").value(0))
                .andExpect(jsonPath("$.sheets[1].sheet").value("Skills"))
                .andExpect(jsonPath("$.sheets[1].total").value(2))
                .andExpect(jsonPath("$.sheets[1].flagged").value(0))
                .andExpect(jsonPath("$.sheets[1].termMiss").value(0))
                .andExpect(jsonPath("$.sheets[1].done").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> rowSheets = JsonPath.parse(body).read("$.rows[*].sheetName");
        assertThat(rowSheets).allMatch("Items"::equals);
    }

    @Test
    void doneCheckOnlyBumpsItsOwnSheet() throws Exception {
        long pid = twoSheetProject();
        String list = mvc.perform(get("/api/projects/{id}/segments", pid).param("sheet", "Items"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long sid = ((Number) JsonPath.parse(list).read("$.rows[0].id")).longValue();

        mvc.perform(put("/api/segments/{id}/done", sid).contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/projects/{id}/segments", pid))
                .andExpect(jsonPath("$.sheets[0].sheet").value("Items"))
                .andExpect(jsonPath("$.sheets[0].done").value(1))
                .andExpect(jsonPath("$.sheets[1].sheet").value("Skills"))
                .andExpect(jsonPath("$.sheets[1].done").value(0));
    }

    @Test
    void ratioChangesFlaggedCountsPerSheet() throws Exception {
        long pid = twoSheetProject();

        mvc.perform(get("/api/projects/{id}/segments", pid).param("ratio", "2.0"))
                .andExpect(jsonPath("$.sheets[0].sheet").value("Items"))
                .andExpect(jsonPath("$.sheets[0].flagged").value(0));
    }

    /**
     * 짧게 다듬기는 시트 탭을 따라간다 — Skills 탭을 보는 사람이 누른 버튼이 Items의 플래그 행까지
     * GPT로 보내면 안 된다. sheet를 주면 그 시트의 플래그 행만 수정본이 생긴다.
     */
    @Test
    void shortenWithASheetOnlyRewritesThatSheetsFlaggedRows() throws Exception {
        FakeLlmClient fake = (FakeLlmClient) llm;
        fake.translations.put("正义裁决", "정의로운 재결이다");   // 원문 4자 → 8자: Skills에도 플래그를 만든다
        long pid = twoSheetProject();
        mvc.perform(get("/api/projects/{id}/segments", pid))
                .andExpect(jsonPath("$.sheets[0].flagged").value(1))
                .andExpect(jsonPath("$.sheets[1].flagged").value(1));

        mvc.perform(post("/api/projects/{id}/shorten", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ratio\":1.0,\"sheet\":\"Items\"}"))
                .andExpect(status().isOk());

        String body = mvc.perform(get("/api/projects/{id}/segments", pid).param("flagged", "false"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> items = JsonPath.parse(body).read("$.rows[?(@.sheetName=='Items')].history[*].author");
        List<String> skills = JsonPath.parse(body).read("$.rows[?(@.sheetName=='Skills')].history[*].author");
        assertThat(items).contains("LLM_SHORTEN");
        assertThat(skills).doesNotContain("LLM_SHORTEN");
    }

    /** sheet를 주지 않으면 예전 그대로 프로젝트 전체를 다듬는다. */
    @Test
    void shortenWithoutASheetStillCoversEverySheet() throws Exception {
        FakeLlmClient fake = (FakeLlmClient) llm;
        fake.translations.put("正义裁决", "정의로운 재결이다");
        long pid = twoSheetProject();

        mvc.perform(post("/api/projects/{id}/shorten", pid).contentType(MediaType.APPLICATION_JSON).content("{\"ratio\":1.0}"))
                .andExpect(status().isOk());

        String body = mvc.perform(get("/api/projects/{id}/segments", pid).param("flagged", "false"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> items = JsonPath.parse(body).read("$.rows[?(@.sheetName=='Items')].history[*].author");
        List<String> skills = JsonPath.parse(body).read("$.rows[?(@.sheetName=='Skills')].history[*].author");
        assertThat(items).contains("LLM_SHORTEN");
        assertThat(skills).contains("LLM_SHORTEN");
    }

    @Test
    void singleSheetProjectReturnsOneEntry() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);

        mvc.perform(get("/api/projects/{id}/segments", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sheets.length()").value(1))
                .andExpect(jsonPath("$.sheets[0].sheet").value("ItemText"));
    }
}
