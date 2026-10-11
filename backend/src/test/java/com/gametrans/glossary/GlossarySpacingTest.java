package com.gametrans.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeJosaClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.activity.Activity;
import com.gametrans.activity.ActivityRepository;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 띄어쓰기 갈림: 표준 꼴(Kiwi) 붙이기와 한꺼번에 통일(행만 고치고 단어장엔 안 남김). */
class GlossarySpacingTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired SegmentRepository segments;
    @Autowired ActivityRepository activities;
    @Autowired FakeJosaClient josa;

    long pid;

    @BeforeEach
    void resetJosa() { josa.reset(); }

    Long game() throws Exception {
        pid = TestProjects.uploadVariants(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE));
        return projects.findById(pid).orElseThrow().getGame().getId();
    }

    private Segment row(String src) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getSrcText().equals(src)).findFirst().orElseThrow();
    }

    private ResultActions unifySpacing(Long g, String json) throws Exception {
        return mvc.perform(post("/api/games/{id}/glossary/unify-spacing", g).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void spacingCardsCarryTheStandardFormWhenKiwiPicksOneOfTheFormsInUse() throws Exception {
        Long g = game();
        josa.spaced.put("멧돼지왕", "멧돼지 왕");
        mvc.perform(get("/api/games/{id}/glossary/variants", g))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.find == '野猪王')].kind").value("SPACING"))
                .andExpect(jsonPath("$[?(@.find == '野猪王')].main.ko").value("멧돼지왕"))
                .andExpect(jsonPath("$[?(@.find == '野猪王')].standard").value("멧돼지 왕"))
                .andExpect(jsonPath("$[?(@.find == '陷阱')].standard").value(org.hamcrest.Matchers.contains((Object) null)));
    }

    @Test
    void noStandardFormWhenKiwiSuggestsAFormNobodyUses() throws Exception {
        Long g = game();
        josa.spaced.put("멧돼지왕", "멧 돼지왕");
        mvc.perform(get("/api/games/{id}/glossary/variants", g))
                .andExpect(jsonPath("$[?(@.find == '野猪王')].standard").value(org.hamcrest.Matchers.contains((Object) null)));
    }

    @Test
    void variantsStillComeBackWhenKiwiIsDown() throws Exception {
        Long g = game();
        josa.failing = true;
        mvc.perform(get("/api/games/{id}/glossary/variants", g))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.find == '野猪王')].kind").value("SPACING"))
                .andExpect(jsonPath("$[?(@.find == '野猪王')].standard").value(org.hamcrest.Matchers.contains((Object) null)));
    }

    @Test
    void unifyingSpacingRewritesRowsWithoutAGlossaryEntry() throws Exception {
        Long g = game();
        unifySpacing(g, "{\"items\":[{\"find\":\"野猪王\",\"ko\":\"멧돼지왕\",\"replace\":[\"멧돼지 왕\"]}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1));
        assertThat(row("野猪王的愤怒").getLatestKo()).isEqualTo("멧돼지왕의 분노");
        assertThat(row("野猪冲锋").getLatestKo()).isEqualTo("멧돼지 돌진");   // 원문에 野猪王이 없다
        mvc.perform(get("/api/games/{id}/glossary", g).param("scope", "GAME")).andExpect(jsonPath("$.length()").value(0));
        assertThat(activities.findByGameIdAndProjectIdIsNullOrderByIdDesc(g)).isEmpty();          // 단어장 편집 없음
        assertThat(activities.findByProjectIdOrderByIdDesc(pid)).extracting(Activity::getKind).containsExactly(Activity.Kind.GLOSSARY_APPLY);
    }

    @Test
    void manyCardsAtOnceMakeOneApplyEntryPerProject() throws Exception {
        Long g = game();
        unifySpacing(g, "{\"items\":[{\"find\":\"野猪王\",\"ko\":\"멧돼지 왕\",\"replace\":[\"멧돼지왕\"]},"
                + "{\"find\":\"每日\",\"ko\":\"매일\",\"replace\":[\"매 일\"]}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(3));
        assertThat(row("击败野猪王").getLatestKo()).isEqualTo("멧돼지 왕 처치");
        assertThat(activities.findByProjectIdOrderByIdDesc(pid)).hasSize(1);
    }

    @Test
    void onlySpacingDifferencesCanBeUnifiedAtOnce() throws Exception {
        Long g = game();
        unifySpacing(g, "{\"items\":[{\"find\":\"每日\",\"ko\":\"매일\",\"replace\":[\"하루에\"]}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("띄어쓰기만 다른 표기만 한꺼번에 고칠 수 있습니다: 하루에 → 매일"));
        assertThat(row("每日刷新").getLatestKo()).isEqualTo("하루에 갱신");
        unifySpacing(g, "{\"items\":[]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("고칠 항목이 없습니다."));
        unifySpacing(999999L, "{\"items\":[{\"find\":\"野猪王\",\"ko\":\"멧돼지왕\",\"replace\":[\"멧돼지 왕\"]}]}").andExpect(status().isNotFound());
    }
}
