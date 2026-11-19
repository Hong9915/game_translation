package com.gametrans.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 찾아 바꾸기 제안. 변형 시트: 501~504 "함정을 …"(陷阱), 701~707 멧돼지(701~704 원문에 野猪王). */
class ConnectorReplaceTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    long pid, gameId, me;

    /** 새 게임에 변형 시트를 올려 정규화하고 프로젝트 id를 돌려준다. */
    static long uploadVariants(MockMvc mvc) throws Exception { return uploadVariants(mvc, TestProjects.freshGame()); }

    static long uploadVariants(MockMvc mvc, String game) throws Exception {
        long pid = TestProjects.uploadVariants(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE)).andExpect(status().isOk());
        return pid;
    }

    static ResultActions replace(MockMvc mvc, Long by, String json) throws Exception {
        var req = post("/api/connector/proposals/replace").contentType(MediaType.APPLICATION_JSON).content(json);
        if (by != null) req.header("X-Reviewer-Id", by);
        return mvc.perform(req);
    }

    static long replaceOk(MockMvc mvc, Long by, String json) throws Exception {
        return JsonPath.parse(replace(mvc, by, json).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .read("$.proposalId", Long.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        pid = uploadVariants(mvc);
        gameId = jdbc.queryForObject("select game_id from project where id = ?", Long.class, pid);
        me = ConnectorSaveTest.newReviewer(mvc);
    }

    ResultActions rep(String json) throws Exception { return replace(mvc, me, json); }

    String g(String rest) { return "{\"gameId\":" + gameId + "," + rest + "}"; }

    DocumentContext json(ResultActions a) throws Exception {
        return JsonPath.parse(a.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 시트의 ID 열 값(501…)으로 행을 찾는다. 헤더가 1행이라 501은 2행, 601은 8행, 701은 15행. */
    long byId(int id) {
        return jdbc.queryForObject("select id from segment where project_id = ? and row_no = ? and skipped = false order by id limit 1", Long.class, pid,
                id < 600 ? id - 499 : id < 700 ? id - 593 : id - 686);
    }

    @Test
    void replaceProposesWithJosaAdjustedAndBaseIsLatestKo() throws Exception {
        var a = rep(g("\"find\":\"함정\",\"replace\":\"올가미\",\"src\":\"陷阱\",\"reason\":\"고객 요청\"")).andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(4)).andExpect(jsonPath("$.proposed").value(4))
                .andExpect(jsonPath("$.locked").value(0)).andExpect(jsonPath("$.srcMissing").value(0))
                .andExpect(jsonPath("$.termCandidate").value(true)).andExpect(jsonPath("$.samples.length()").value(3))
                .andExpect(jsonPath("$.samples[0].before").value("함정을 설치"))
                .andExpect(jsonPath("$.samples[0].after").value("올가미를 설치"));
        long id = json(a).read("$.proposalId", Long.class);
        var d = json(mvc.perform(get("/api/connector/proposals/{id}", id)).andExpect(status().isOk()));
        assertThat(d.read("$.rows.length()", Integer.class)).isEqualTo(4);
        assertThat(d.read("$.termSrc", String.class)).isEqualTo("陷阱");
        assertThat(d.read("$.title", String.class)).isEqualTo("함정 → 올가미");
        for (int i = 0; i < 4; i++) {
            long sid = d.read("$.rows[" + i + "].segmentId", Long.class);
            String ko = jdbc.queryForObject("select latest_ko from segment where id = ?", String.class, sid);
            assertThat(d.read("$.rows[" + i + "].base", String.class)).isEqualTo(ko);
        }
    }

    @Test
    void termCandidateFalseWhenGlossaryAlreadyHasSameKo() throws Exception {
        jdbc.update("insert into glossary (game_id, kind, match_mode, find, ko) values (?, 'SRC', 'PARTIAL', '陷阱', '올가미')", gameId);
        rep(g("\"find\":\"함정\",\"replace\":\"올가미\",\"src\":\"陷阱\"")).andExpect(status().isOk())
                .andExpect(jsonPath("$.termCandidate").value(false));
    }

    @Test
    void termCandidateTrueWhenGlossaryHasDifferentKo() throws Exception {
        jdbc.update("insert into glossary (game_id, kind, match_mode, find, ko) values (?, 'SRC', 'PARTIAL', '陷阱', '함정')", gameId);
        rep(g("\"find\":\"함정\",\"replace\":\"올가미\",\"src\":\"陷阱\"")).andExpect(status().isOk())
                .andExpect(jsonPath("$.termCandidate").value(true));
    }

    @Test
    void proposeNeverSavesRows() throws Exception {
        var before = jdbc.queryForList("select id || ':' || coalesce(latest_ko, '') from segment order by id", Object.class);
        long revs = jdbc.queryForObject("select count(*) from translation_revision", Long.class);
        rep(g("\"find\":\"함정\",\"replace\":\"올가미\",\"src\":\"陷阱\"")).andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(4));
        assertThat(jdbc.queryForList("select id || ':' || coalesce(latest_ko, '') from segment order by id", Object.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from translation_revision", Long.class)).isEqualTo(revs);
    }

    @Test
    void replacesEveryOccurrenceInRow() throws Exception {
        jdbc.update("update segment set latest_ko = '함정을 설치한 함정지기' where id = ?", byId(501));
        long id = replaceOkG("\"find\":\"함정\",\"replace\":\"올가미\"");
        var d = json(mvc.perform(get("/api/connector/proposals/{id}", id)));
        assertThat(d.read("$.rows[?(@.segmentId==" + byId(501) + ")].text", java.util.List.class)).containsExactly("올가미를 설치한 올가미지기");
    }

    long replaceOkG(String rest) throws Exception { return replaceOk(mvc, me, g(rest)); }

    @Test
    void confirmedRowsAreCountedAsLockedAndLeftOut() throws Exception {
        jdbc.update("update segment set confirmed = true where id = ?", byId(502));
        rep(g("\"find\":\"함정\",\"replace\":\"올가미\"")).andExpect(status().isOk())
                .andExpect(jsonPath("$.proposed").value(3)).andExpect(jsonPath("$.locked").value(1));
    }

    @Test
    void srcOnFewerThanHalfIsRejected() throws Exception {
        rep(g("\"find\":\"멧돼지\",\"replace\":\"산돼지\",\"src\":\"野猪王的\"")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("원문 용어 '野猪王的'가 걸린 행 7개 중 1개의 원문에만 있습니다 — 원문을 다시 확인하세요"));
    }

    @Test
    void srcMissingRowsAreMarked() throws Exception {
        var a = rep(g("\"find\":\"멧돼지\",\"replace\":\"산돼지\",\"src\":\"野猪王\"")).andExpect(status().isOk())
                .andExpect(jsonPath("$.proposed").value(7)).andExpect(jsonPath("$.srcMissing").value(3));
        long id = json(a).read("$.proposalId", Long.class);
        var d = json(mvc.perform(get("/api/connector/proposals/{id}", id)));
        assertThat(d.read("$.rows[?(@.srcMissing==true)].segmentId", java.util.List.class))
                .containsExactlyInAnyOrder((int) byId(705), (int) byId(706), (int) byId(707));
    }

    @Test
    void noMatchIs400() throws Exception {
        rep(g("\"find\":\"없는말\",\"replace\":\"x\"")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("찾을 말이 있는 행이 없습니다"));
    }

    @Test
    void exceptRowIdsOver500Is400() throws Exception {
        String ids = IntStream.rangeClosed(1, 501).mapToObj(String::valueOf).collect(Collectors.joining(","));
        rep(g("\"find\":\"함정\",\"replace\":\"올가미\",\"exceptRowIds\":[" + ids + "]")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("exceptRowIds는 500개까지입니다"));
    }

    @Test
    void tooManyRowsMessage() {
        assertThatThrownBy(() -> ConnectorReplaceService.checkCount(501)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("501행이 걸렸습니다. projectId·sheet로 좁히거나 exceptRowIds를 쓰세요");
        ConnectorReplaceService.checkCount(500);
    }

    @Test
    void rejectsSameWordAndBlankTermReplace() throws Exception {
        rep(g("\"find\":\"함정\",\"replace\":\"함정\"")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("바꿀 말이 찾을 말과 같습니다"));
        rep(g("\"find\":\"함정\",\"replace\":\"\",\"src\":\"陷阱\"")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("용어 바꾸기에는 바꿀 말이 필요합니다"));
        rep(g("\"find\":\"\",\"replace\":\"x\"")).andExpect(status().isBadRequest());
    }

    @Test
    void gameScopeUsesFinalLaneOnlyAndProjectIdReachesOldFile() throws Exception {
        long fin = TestProjects.uploadFinalSameSourceTwice(mvc, jdbc.queryForObject("select name from game where id = ?", String.class, gameId));
        mvc.perform(put("/api/projects/{id}/normalize", fin).contentType(MediaType.APPLICATION_JSON).content(TestProjects.FINAL_SKILL_NORMALIZE)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select game_id from project where id = ?", Long.class, fin)).isEqualTo(gameId);
        rep(g("\"find\":\"함정\",\"replace\":\"올가미\"")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("찾을 말이 있는 행이 없습니다"));
        rep("{\"projectId\":" + pid + ",\"find\":\"함정\",\"replace\":\"올가미\"}").andExpect(status().isOk()).andExpect(jsonPath("$.proposed").value(4));
    }

    @Test
    void exceptRowIdsLeavesRowsOut() throws Exception {
        rep(g("\"find\":\"함정\",\"replace\":\"올가미\",\"exceptRowIds\":[" + byId(501) + "]")).andExpect(status().isOk()).andExpect(jsonPath("$.proposed").value(3));
    }
}
