package com.gametrans.josa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** 조사 제안 적용·무시·미리보기(설계 2026-10-07 §5). */
class JosaApplyTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired RevisionService revisions;
    @Autowired JdbcTemplate jdbc;

    static final String TWO = "아군의 생명를 주고 면역가 오름";
    static final String TWO_HINTS = "[{\"at\":4,\"from\":\"생명를\",\"to\":\"생명을\",\"kind\":\"HANGUL\"},{\"at\":11,\"from\":\"면역가\",\"to\":\"면역이\",\"kind\":\"HANGUL\"}]";
    static final String NUM = "횟수가 <color=1>3</color>로 증가";
    static final String NUM_HINT = "[{\"at\":" + NUM.indexOf("로") + ",\"from\":\"로\",\"to\":\"으로\",\"kind\":\"NUMBER\"}]";
    static final String TAG = "[방어 증가]을 추가";
    static final String TAG_HINT = "[{\"at\":7,\"from\":\"을\",\"to\":\"를\",\"kind\":\"TAG\"}]";

    long pid;
    List<Segment> rs;

    void setup() throws Exception {
        pid = TestProjects.uploadExistingKo(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE))
                .andExpect(status().isOk());
        rs = segments.findByProjectIdAndSheetNameAndSkippedFalseOrderByRowNoAscPairIndexAsc(pid, "Skill");
        seed(0, TWO, TWO_HINTS);
        seed(1, NUM, NUM_HINT);
        seed(2, TAG, TAG_HINT);
    }

    void seed(int i, String text, String hint) {
        Long id = rs.get(i).getId();
        revisions.add(id, text, TranslationRevision.Author.HUMAN);
        jdbc.update("update segment set josa_checked = true, josa_hint = ?::jsonb where id = ?", hint, id);
    }

    String ko(int i) { return segments.findById(rs.get(i).getId()).orElseThrow().getLatestKo(); }

    @Test
    void applyFixesSeveralHintsRightToLeft() throws Exception {
        setup();
        mvc.perform(post("/api/review/{id}/josa/apply", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segmentIds\":[" + rs.get(0).getId() + "],\"kinds\":[\"HANGUL\",\"NUMBER\",\"TAG\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(1));
        assertThat(ko(0)).isEqualTo("아군의 생명을 주고 면역이 오름");
        assertThat(ko(1)).isEqualTo(NUM);
    }

    @Test
    void sheetApplyOnlyChosenKinds() throws Exception {
        setup();
        mvc.perform(post("/api/review/{id}/josa/apply", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sheet\":\"Skill\",\"kinds\":[\"HANGUL\",\"NUMBER\"]}"))
                .andExpect(jsonPath("$.changed").value(2));
        assertThat(ko(0)).isEqualTo("아군의 생명을 주고 면역이 오름");
        assertThat(ko(1)).isEqualTo("횟수가 <color=1>3</color>으로 증가");
        assertThat(ko(2)).isEqualTo(TAG);
    }

    @Test
    void confirmedRowsAreFixedAndRevertable() throws Exception {
        // 2026-10-07 사용자 결정: 조사 고침은 확정 행도 고친다(같은 원문 반영·단어장 일괄 적용과 달리). 되돌리기도 확정 행까지 돌린다.
        setup();
        jdbc.update("update segment set confirmed = true where id = ?", rs.get(0).getId());
        mvc.perform(get("/api/review/{id}/josa/preview", pid).param("sheet", "Skill"))
                .andExpect(jsonPath("$.counts.HANGUL").value(2));
        mvc.perform(post("/api/review/{id}/josa/apply", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sheet\":\"Skill\",\"kinds\":[\"HANGUL\"]}"))
                .andExpect(jsonPath("$.changed").value(1)).andExpect(jsonPath("$.skipped").value(0));
        assertThat(ko(0)).isEqualTo("아군의 생명을 주고 면역이 오름");
        assertThat(segments.findById(rs.get(0).getId()).orElseThrow().isConfirmed()).isTrue();

        String feed = mvc.perform(get("/api/projects/{id}/activity", pid)).andReturn().getResponse().getContentAsString();
        List<Integer> josaIds = JsonPath.read(feed, "$.items[?(@.kind=='JOSA_FIX')].id");
        mvc.perform(post("/api/activity/{id}/revert", josaIds.get(0))).andExpect(status().isOk());
        assertThat(ko(0)).isEqualTo(TWO);
    }

    @Test
    void oneActivityAndRevertRestores() throws Exception {
        setup();
        mvc.perform(post("/api/review/{id}/josa/apply", pid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sheet\":\"Skill\",\"kinds\":[\"HANGUL\",\"NUMBER\"]}")).andExpect(status().isOk());
        String feed = mvc.perform(get("/api/projects/{id}/activity", pid)).andReturn().getResponse().getContentAsString();
        List<String> kinds = JsonPath.read(feed, "$.items[*].kind");
        assertThat(kinds).containsOnlyOnce("JOSA_FIX");
        List<Integer> josaIds = JsonPath.read(feed, "$.items[?(@.kind=='JOSA_FIX')].id");
        mvc.perform(post("/api/activity/{id}/revert", josaIds.get(0))).andExpect(status().isOk());
        assertThat(ko(0)).isEqualTo(TWO);
        assertThat(ko(1)).isEqualTo(NUM);
    }

    @Test
    void ignoreHidesUntilTextChanges() throws Exception {
        setup();
        Long id = rs.get(0).getId();
        mvc.perform(post("/api/review/{id}/josa/ignore", pid).contentType(MediaType.APPLICATION_JSON).content("{\"segmentId\":" + id + "}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("filter", "JOSA"))
                .andExpect(jsonPath("$.rows[?(@.id==" + id + ")]").isEmpty());
        revisions.add(id, "아군의 생명를 줌", TranslationRevision.Author.HUMAN);
        assertThat(segments.findById(id).orElseThrow().isJosaIgnored()).isFalse();
    }

    @Test
    void previewCountsAndExamples() throws Exception {
        setup();
        mvc.perform(get("/api/review/{id}/josa/preview", pid).param("sheet", "Skill"))
                .andExpect(jsonPath("$.counts.HANGUL").value(2))
                .andExpect(jsonPath("$.counts.NUMBER").value(1))
                .andExpect(jsonPath("$.counts.TAG").value(1))
                .andExpect(jsonPath("$.examples.NUMBER[0]").value("3로 → 3으로"));
    }

    @Test
    void applyNeedsKinds() throws Exception {
        setup();
        mvc.perform(post("/api/review/{id}/josa/apply", pid).contentType(MediaType.APPLICATION_JSON).content("{\"sheet\":\"Skill\",\"kinds\":[]}"))
                .andExpect(status().isBadRequest());
    }
}
