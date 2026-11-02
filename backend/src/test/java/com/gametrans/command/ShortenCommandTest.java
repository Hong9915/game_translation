package com.gametrans.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.llm.LlmClient;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 명령으로 고치기의 길이 줄이기(2026-10-13): 길이 넘는 행 목록 → 묶음마다 GPT로 줄인 미리 보기 → 확인만 하고 저장. */
class ShortenCommandTest extends IntegrationTest {
    static final Map<String, Object> SHORTEN = Map.of("kind", "SHORTEN");

    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired RevisionService revisions;
    @Autowired LlmClient llmBean;
    @Autowired ObjectMapper json;

    FakeLlmClient llm;
    long pid;

    @BeforeEach
    void setUp() throws Exception {
        llm = (FakeLlmClient) llmBean;
        llm.shortenCalls.clear();
        pid = TestProjects.uploadVariants(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE));
    }

    private Segment row(String src) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getSrcText().equals(src)).findFirst().orElseThrow();
    }

    private Segment set(String src, String ko) {
        revisions.add(row(src).getId(), ko, TranslationRevision.Author.HUMAN);
        return row(src);
    }

    private ResultActions plan(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/review/{id}/command-fix/plan", pid).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions fix(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/review/{id}/command-fix", pid).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    /** 비율 2.0: 陷阱伤害(4자) 목표 8자. "함정에 의해 받는 피해량"(10자)은 넘고 放置陷阱 "함정을 설치"(5자)는 안 넘는다. */
    @Test
    void overLengthListsRowsPastTheRatioWithTheirTarget() throws Exception {
        Segment long1 = set("陷阱伤害", "함정에 의해 받는 피해량");
        Segment ok = row("放置陷阱");
        String body = plan(Map.of("sheet", "Skill", "check", "OVER_LENGTH", "ratio", 2.0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("CHECK"))
                .andReturn().getResponse().getContentAsString();
        List<Integer> ids = com.jayway.jsonpath.JsonPath.read(body, "$.rows[*].segmentId");
        assertThat(ids).contains(long1.getId().intValue()).doesNotContain(ok.getId().intValue());
        List<Integer> targets = com.jayway.jsonpath.JsonPath.read(body, "$.rows[?(@.segmentId == " + long1.getId() + ")].target");
        assertThat(targets).containsExactly(8);
    }

    @Test
    void shortenPreviewPicksTheLongestCandidateWithinTheTarget() throws Exception {
        Segment s = set("陷阱伤害", "함정에 의해 받는 피해량");
        llm.shortenOptions = List.of("함정 피해", "함정에 받는 피해", "함정에 의해 받는 피해량이 큼");
        plan(Map.of("sheet", "Skill", "ops", List.of(SHORTEN), "only", List.of(s.getId()), "ratio", 2.0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("FIX"))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].before").value("함정에 의해 받는 피해량"))
                .andExpect(jsonPath("$.rows[0].after").value("함정에 받는 피해"))
                .andExpect(jsonPath("$.rows[0].target").value(8))
                .andExpect(jsonPath("$.rows[0].by[0]").value("SHORTEN"))
                .andExpect(jsonPath("$.rows[0].warnings.length()").value(0));
        assertThat(llm.shortenCalls).hasSize(1);
        assertThat(llm.shortenCalls.get(0).maxChars()).isEqualTo(8);
    }

    @Test
    void candidateStillPastTheTargetIsKeptWithAWarning() throws Exception {
        Segment s = set("陷阱伤害", "함정에 의해 받는 피해량");
        llm.shortenOptions = List.of("함정에 의해 받는 피해");
        plan(Map.of("sheet", "Skill", "ops", List.of(SHORTEN), "only", List.of(s.getId()), "ratio", 2.0))
                .andExpect(jsonPath("$.rows[0].after").value("함정에 의해 받는 피해"))
                .andExpect(jsonPath("$.rows[0].warnings[0].kind").value("LENGTH"));
    }

    @Test
    void shortenNeedsChosenRowsAndCannotBeChained() throws Exception {
        plan(Map.of("sheet", "Skill", "ops", List.of(SHORTEN))).andExpect(status().isBadRequest());
        plan(Map.of("sheet", "Skill", "ops", List.of(SHORTEN, Map.of("kind", "JOSA")), "only", List.of(1))).andExpect(status().isBadRequest());
    }

    /** 저장은 다시 줄이지 않는다: 지금 번역 그대로·짧아짐·태그 짝만 보고 쓴다. 길어진 것과 그 사이 바뀐 행은 건너뛴다. */
    @Test
    void fixWritesOnlyShorterUnchangedRows() throws Exception {
        Segment a = set("陷阱伤害", "함정에 의해 받는 피해량");
        Segment b = set("一日游", "하루 만에 끝나는 짧은 여행");
        Segment c = set("每日奖励", "매일 받을 수 있는 보상");
        fix(Map.of("sheet", "Skill", "command", "길이 줄이기", "ops", List.of(SHORTEN), "items", List.of(
                Map.of("segmentId", a.getId(), "before", "함정에 의해 받는 피해량", "after", "함정 피해"),
                Map.of("segmentId", b.getId(), "before", "하루 만에 끝나는 짧은 여행", "after", "하루 만에 끝나는 아주 아주 짧은 여행"),
                Map.of("segmentId", c.getId(), "before", "옛 번역", "after", "매일 보상"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1));
        assertThat(row("陷阱伤害").getLatestKo()).isEqualTo("함정 피해");
        assertThat(row("一日游").getLatestKo()).isEqualTo("하루 만에 끝나는 짧은 여행");
        assertThat(row("每日奖励").getLatestKo()).isEqualTo("매일 받을 수 있는 보상");
    }

    @Test
    void pickPrefersTheLongestFitThenTheShortest() {
        var c = List.of(new com.gametrans.translate.TranslateDtos.ShortenCandidate("가나다라마", 5),
                new com.gametrans.translate.TranslateDtos.ShortenCandidate("가나다", 3),
                new com.gametrans.translate.TranslateDtos.ShortenCandidate("가나다라", 4));
        assertThat(BulkShorten.pick(c, 4)).isEqualTo("가나다라");
        assertThat(BulkShorten.pick(c, 2)).isEqualTo("가나다");
        assertThat(BulkShorten.pick(List.of(), 2)).isNull();
    }

    @Test
    void acceptableNeedsShorterTextWithTheSameTags() {
        assertThat(BulkShorten.acceptable("<b>함정</b>에 의한 피해", "<b>함정</b> 피해")).isTrue();
        assertThat(BulkShorten.acceptable("<b>함정</b>에 의한 피해", "함정 피해")).isFalse();
        assertThat(BulkShorten.acceptable("함정 피해", "함정에 의한 피해")).isFalse();
        assertThat(BulkShorten.acceptable("함정 피해", " ")).isFalse();
    }
}
