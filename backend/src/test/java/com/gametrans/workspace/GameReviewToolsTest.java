package com.gametrans.workspace;

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
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 게임 전체 리뷰의 도구(설계 2026-10-12 §3): 일괄 체크·태그 띄어쓰기·용어 재계산이 고른 범위에 걸린다. 같은 게임에 같은 파일 둘. */
class GameReviewToolsTest extends IntegrationTest {
    static final String JOINED = "적에게<color=red>함정</color>피해";
    static final String SPACED = "적에게 <color=red>함정</color> 피해";

    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired ActivityRepository activities;
    @Autowired RevisionService revisions;
    @Autowired FakeJosaClient josa;

    long gameId, a, b, sheetA, sheetB;

    @BeforeEach
    void setUp() throws Exception {
        josa.reset();
        josa.tagSpaced.put(JOINED, SPACED);
        String game = TestProjects.freshGame();
        a = project(game);
        b = project(game);
        gameId = ((Number) JsonPath.read(mvc.perform(get("/api/projects/{id}", a)).andReturn().getResponse().getContentAsString(), "$.gameId")).longValue();
        List<Number> ids = JsonPath.read(mvc.perform(get("/api/games/{g}/review/rows/summary", gameId)).andReturn().getResponse().getContentAsString(), "$.sheets[*].configId");
        sheetA = ids.get(0).longValue();
        sheetB = ids.get(1).longValue();
    }

    long project(String game) throws Exception {
        long pid = TestProjects.uploadVariants(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE))
                .andExpect(status().isOk());
        return pid;
    }

    Segment row(long pid, String src) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getSrcText().equals(src)).findFirst().orElseThrow();
    }

    long total(long pid) { return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).size(); }

    @Test
    void bulkMarksCoverEveryFileInScopeAndIdsOutsideTheScopeAreIgnored() throws Exception {
        mvc.perform(post("/api/games/{g}/review/marks", gameId).contentType(MediaType.APPLICATION_JSON).content("{\"review1\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value((int) (total(a) + total(b))));

        long inA = row(a, "陷阱伤害").getId(), inB = row(b, "陷阱伤害").getId();
        mvc.perform(post("/api/games/{g}/review/marks", gameId).param("sheets", String.valueOf(sheetA)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"review2\":true,\"ids\":[" + inA + "," + inB + "]}"))
                .andExpect(jsonPath("$.updated").value(1));
        assertThat(segments.findById(inA).orElseThrow().isReview2()).isTrue();
        assertThat(segments.findById(inB).orElseThrow().isReview2()).isFalse();
    }

    @Test
    void tagSpacingListsAndFixesAcrossFilesWithOneActivityPerFile() throws Exception {
        Segment sa = row(a, "陷阱伤害"), sb = row(b, "陷阱伤害");
        revisions.add(sa.getId(), JOINED, TranslationRevision.Author.HUMAN);
        revisions.add(sb.getId(), JOINED, TranslationRevision.Author.HUMAN);

        mvc.perform(get("/api/games/{g}/review/tag-spacing", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].projectId").value(a))
                .andExpect(jsonPath("$[0].sheet").value("Skill"))
                .andExpect(jsonPath("$[1].projectId").value(b));
        mvc.perform(get("/api/games/{g}/review/tag-spacing", gameId).param("sheets", String.valueOf(sheetB)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].projectId").value(b));

        String items = "[" + item(sa) + "," + item(sb) + "]";
        mvc.perform(post("/api/games/{g}/review/tag-spacing", gameId).contentType(MediaType.APPLICATION_JSON).content("{\"items\":" + items + "}"))
                .andExpect(jsonPath("$.applied").value(2));
        assertThat(segments.findById(sa.getId()).orElseThrow().getLatestKo()).isEqualTo(SPACED);
        assertThat(segments.findById(sb.getId()).orElseThrow().getLatestKo()).isEqualTo(SPACED);
        assertThat(activities.findByProjectIdOrderByIdDesc(a).stream().filter(x -> x.getKind() == Activity.Kind.TAG_SPACING)).hasSize(1);
        assertThat(activities.findByProjectIdOrderByIdDesc(b).stream().filter(x -> x.getKind() == Activity.Kind.TAG_SPACING)).hasSize(1);
    }

    @Test
    void tagSpacingFixSkipsRowsOutsideTheScope() throws Exception {
        Segment sa = row(a, "陷阱伤害"), sb = row(b, "陷阱伤害");
        revisions.add(sa.getId(), JOINED, TranslationRevision.Author.HUMAN);
        revisions.add(sb.getId(), JOINED, TranslationRevision.Author.HUMAN);
        String items = "[" + item(sa) + "," + item(sb) + "]";
        mvc.perform(post("/api/games/{g}/review/tag-spacing", gameId).param("sheets", String.valueOf(sheetA))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":" + items + "}"))
                .andExpect(jsonPath("$.applied").value(1));
        assertThat(segments.findById(sb.getId()).orElseThrow().getLatestKo()).isEqualTo(JOINED);
    }

    @Test
    void termMissRecomputeRunsOverTheScope() throws Exception {
        mvc.perform(post("/api/games/{g}/review/term-miss/recompute", gameId)).andExpect(status().isOk()).andExpect(jsonPath("$.count").isNumber());
    }

    @Test
    void josaIgnoreRejectsARowOfAnotherGame() throws Exception {
        long other = TestProjects.uploadVariants(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", other).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE));
        long foreign = row(other, "陷阱伤害").getId();
        mvc.perform(post("/api/games/{g}/review/josa/ignore", gameId).contentType(MediaType.APPLICATION_JSON).content("{\"segmentId\":" + foreign + "}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void commandPlanAndFixSpanFilesAndTheGameFeedShowsBoth() throws Exception {
        Segment sa = row(a, "陷阱伤害"), sb = row(b, "陷阱伤害");
        revisions.add(sa.getId(), JOINED, TranslationRevision.Author.HUMAN);
        revisions.add(sb.getId(), JOINED, TranslationRevision.Author.HUMAN);
        String ops = "[{\"kind\":\"REPLACE\",\"steps\":[{\"find\":\"함정\",\"replace\":\"덫\",\"regex\":false}]}]";
        String plan = mvc.perform(post("/api/games/{g}/review/command-fix/plan", gameId).contentType(MediaType.APPLICATION_JSON).content("{\"ops\":" + ops + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("FIX"))
                .andExpect(jsonPath("$.rows[?(@.segmentId==" + sa.getId() + ")].projectId").value(org.hamcrest.Matchers.contains((int) a)))
                .andExpect(jsonPath("$.rows[?(@.segmentId==" + sb.getId() + ")].sheet").value(org.hamcrest.Matchers.contains("Skill")))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String after = JOINED.replace("함정", "덫");
        assertThat(plan).contains(after);
        String items = "[{\"segmentId\":" + sa.getId() + ",\"before\":\"" + JOINED + "\",\"after\":\"" + after + "\"},"
                + "{\"segmentId\":" + sb.getId() + ",\"before\":\"" + JOINED + "\",\"after\":\"" + after + "\"}]";
        mvc.perform(post("/api/games/{g}/review/command-fix", gameId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"\",\"ops\":" + ops + ",\"items\":" + items + "}"))
                .andExpect(jsonPath("$.applied").value(2));

        mvc.perform(get("/api/games/{g}/review/activity", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.kind=='COMMAND_FIX')].projectId").value(org.hamcrest.Matchers.containsInAnyOrder((int) a, (int) b)));
        // 범위를 한 파일로 좁히면 그 파일 항목만
        mvc.perform(get("/api/games/{g}/review/activity", gameId).param("sheets", String.valueOf(sheetA)))
                .andExpect(jsonPath("$.items[?(@.kind=='COMMAND_FIX')].projectId").value(org.hamcrest.Matchers.contains((int) a)));
    }

    @Test
    void commandPresetsAreSharedBetweenFileAndGameScreens() throws Exception {
        String body = "{\"name\":\"덫으로\",\"ops\":[{\"kind\":\"REPLACE\",\"steps\":[{\"find\":\"함정\",\"replace\":\"덫\",\"regex\":false}]}]}";
        String saved = mvc.perform(post("/api/games/{g}/review/command-presets", gameId).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long presetId = ((Number) JsonPath.read(saved, "$.id")).longValue();
        mvc.perform(get("/api/review/{id}/command-presets", a)).andExpect(jsonPath("$[0].name").value("덫으로"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/games/{g}/review/command-presets/{p}", gameId, presetId))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/games/{g}/review/command-presets", gameId)).andExpect(jsonPath("$.length()").value(0));
    }

    private static String item(Segment s) {
        return "{\"segmentId\":" + s.getId() + ",\"before\":\"" + JOINED + "\",\"after\":\"" + SPACED + "\"}";
    }
}
