package com.gametrans.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.activity.Activity;
import com.gametrans.activity.ActivityRepository;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class GlossaryVariantsControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;

    long pid;

    Long game() throws Exception {
        pid = TestProjects.uploadVariants(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE));
        return projects.findById(pid).orElseThrow().getGame().getId();
    }

    @Test
    void listsPhrasesWhoseRenderingIsSplitUntilTheyAreInTheGlossary() throws Exception {
        Long g = game();
        mvc.perform(get("/api/games/{id}/glossary/variants", g))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.find == '陷阱')].kind").value("ALT"))
                .andExpect(jsonPath("$[?(@.find == '陷阱')].main.ko").value("함정"))
                .andExpect(jsonPath("$[?(@.find == '陷阱')].alts[0].ko").value("트랩"))
                .andExpect(jsonPath("$[?(@.find == '陷阱')].alts[0].rows").value(2));
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"SRC\",\"matchMode\":\"PARTIAL\",\"find\":\"陷阱\",\"ko\":\"함정\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/games/{id}/glossary/variants", g))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.find == '陷阱')]").isEmpty());
    }

    @Test
    void variantsRejectsUnknownGame() throws Exception {
        mvc.perform(get("/api/games/{id}/glossary/variants", 999999L)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("게임을 찾을 수 없습니다: 999999"));
    }

    @Autowired SegmentRepository segments;
    @Autowired ActivityRepository activities;

    private Segment row(String src) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getSrcText().equals(src)).findFirst().orElseThrow();
    }

    private org.springframework.test.web.servlet.ResultActions unify(Long g, String json) throws Exception {
        return mvc.perform(post("/api/games/{id}/glossary/unify", g).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void unifyingEveryOtherRenderingRewritesThoseRowsAndAddsATranslationEntry() throws Exception {
        Long g = game();
        unify(g, "{\"find\":\"每日\",\"ko\":\"매일\",\"replace\":[\"일일\",\"하루에\"],\"keep\":[]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(4))
                .andExpect(jsonPath("$.entry.kind").value("SRC"))
                .andExpect(jsonPath("$.entry.matchMode").value("PARTIAL"))
                .andExpect(jsonPath("$.entry.find").value("每日"))
                .andExpect(jsonPath("$.entry.ko").value("매일"))
                .andExpect(jsonPath("$.entry.note").isEmpty())
                .andExpect(jsonPath("$.entry.oldKo").isEmpty());
        assertThat(row("每日任务").getLatestKo()).isEqualTo("매일 임무");
        assertThat(row("每日刷新").getLatestKo()).isEqualTo("매일 갱신");
        assertThat(row("一日游").getLatestKo()).isEqualTo("하루에 끝나는 여행");   // 원문에 每日이 없다
    }

    @Test
    void keepingARenderingRewritesOnlyTheCheckedOnesAndLeavesAMemo() throws Exception {
        Long g = game();
        unify(g, "{\"find\":\"每日\",\"ko\":\"매일\",\"replace\":[\"하루에\"],\"keep\":[\"일일\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(2))
                .andExpect(jsonPath("$.entry.ko").isEmpty())
                .andExpect(jsonPath("$.entry.note").value("매일로 통일, 일일도 씀"));
        assertThat(row("每日可领取一次").getLatestKo()).isEqualTo("매일 한 번 받을 수 있음");
        assertThat(row("每日任务").getLatestKo()).isEqualTo("일일 임무");
        assertThat(row("一日游").getLatestKo()).isEqualTo("하루에 끝나는 여행");
    }

    @Test
    void aLongerRenderingIsReplacedBeforeAShorterOneThatStartsIt() throws Exception {
        Long g = game();
        unify(g, "{\"find\":\"每日\",\"ko\":\"매일\",\"replace\":[\"하루\",\"하루에\"],\"keep\":[\"일일\"]}").andExpect(status().isOk());
        assertThat(row("每日可领取一次").getLatestKo()).isEqualTo("매일 한 번 받을 수 있음");   // "매일에 한 번"이 아니다
    }

    @Test
    void unifyingSkipsConfirmedRows() throws Exception {
        Long g = game();
        mvc.perform(put("/api/segments/{id}/marks", row("每日刷新").getId()).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"))
                .andExpect(status().isOk());
        unify(g, "{\"find\":\"每日\",\"ko\":\"매일\",\"replace\":[\"하루에\"],\"keep\":[\"일일\"]}").andExpect(jsonPath("$.applied").value(1));
        assertThat(row("每日刷新").getLatestKo()).isEqualTo("하루에 갱신");
    }

    @Test
    void unifyingIntoAFormThatContainsTheOldOneDoesNotGrowIt() throws Exception {
        Long g = game();
        // 맞출 표기가 바꿀 표기를 품는다: "트랩" → "트랩 장치". replacePartial이 이미 바뀐 자리를 다시 건드리지 않는다
        unify(g, "{\"find\":\"陷阱\",\"ko\":\"트랩 장치\",\"replace\":[\"트랩\"],\"keep\":[\"함정\"]}").andExpect(jsonPath("$.applied").value(2));
        assertThat(row("陷阱伤害").getLatestKo()).isEqualTo("트랩 장치 피해");
        assertThat(row("陷阱强化").getLatestKo()).isEqualTo("트랩 장치 강화");
    }

    @Test
    void unifyingLogsAGlossaryEditForTheGameAndAnApplyForTheProject() throws Exception {
        Long g = game();
        unify(g, "{\"find\":\"每日\",\"ko\":\"매일\",\"replace\":[\"하루에\"],\"keep\":[\"일일\"]}").andExpect(status().isOk());
        assertThat(activities.findByGameIdAndProjectIdIsNullOrderByIdDesc(g)).singleElement().satisfies(e -> {
            assertThat(e.getKind()).isEqualTo(Activity.Kind.GLOSSARY_EDIT);
            assertThat(e.getDetail()).containsEntry("action", "ADD").containsEntry("find", "每日");
        });
        assertThat(activities.findByProjectIdOrderByIdDesc(pid)).extracting(Activity::getKind).containsExactly(Activity.Kind.GLOSSARY_APPLY);
    }

    @Test
    void unifyingAPhraseAlreadyInTheGlossaryIsRejectedWithoutTouchingRows() throws Exception {
        Long g = game();
        unify(g, "{\"find\":\"每日\",\"ko\":\"매일\",\"replace\":[\"하루에\"],\"keep\":[\"일일\"]}").andExpect(status().isOk());
        unify(g, "{\"find\":\"每日\",\"ko\":\"일일\",\"replace\":[\"매일\"],\"keep\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이미 단어장에 있는 원문입니다: 每日"));
        assertThat(row("每日签到").getLatestKo()).isEqualTo("매일 출석");
    }

    @Test
    void unifyValidatesItsInput() throws Exception {
        Long g = game();
        unify(g, "{\"find\":\"每日\",\"ko\":\" \",\"replace\":[\"하루에\"],\"keep\":[]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("맞출 표기를 입력하세요."));
        unify(g, "{\"find\":\"每日\",\"ko\":\"매일\",\"replace\":[\" \"],\"keep\":[]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("바꿀 표기를 하나 이상 고르세요."));
        unify(g, "{\"find\":\"每日\",\"ko\":\"매일\",\"replace\":[\"매일\"],\"keep\":[]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("맞출 표기와 바꿀 표기가 같습니다: 매일"));
        unify(999999L, "{\"find\":\"每日\",\"ko\":\"매일\",\"replace\":[\"하루에\"],\"keep\":[]}").andExpect(status().isNotFound());
    }
}
