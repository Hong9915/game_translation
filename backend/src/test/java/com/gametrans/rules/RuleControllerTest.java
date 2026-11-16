package com.gametrans.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.ProjectRepository;
import com.gametrans.review.ReviewService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class RuleControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ReviewService reviews;
    @Autowired SegmentRepository segments;
    @Autowired ProjectRepository projects;
    @Autowired CorrectionRuleRepository rules;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    long pid;
    Long gameId;

    void setup() throws Exception {
        pid = TestProjects.uploadExistingKoSameSourceTwice(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));
        gameId = projects.findById(pid).orElseThrow().getGame().getId();
        long sid = row(2).getId();
        jdbc.update("update segment set text_kind = 'S' where id = ?", sid);
        reviews.saveHuman(sid, "탈퇴할 수 없습니다.", null, null);
        reviews.saveHuman(sid, "탈퇴 불가능.", null, null);
    }

    Segment row(int rowNo) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getRowNo() == rowNo).findFirst().orElseThrow();
    }

    JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return json.readTree(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    JsonNode style(JsonNode list) {
        for (JsonNode r : list.path("rules")) if (r.path("type").asText().equals("STYLE")) return r;
        return null;
    }

    @Test
    void stylePolicyOverridesVerdict() throws Exception {
        setup();
        JsonNode s = style(body(mvc.perform(post("/api/games/{g}/rules/relearn", gameId))));
        assertThat(s.path("textKind").asText()).isEqualTo("S");
        assertThat(s.path("verdict").asText()).isEqualTo("INSUFFICIENT");
        JsonNode after = body(mvc.perform(put("/api/games/{g}/style-policy/{k}", gameId, "S").contentType(MediaType.APPLICATION_JSON)
                .content("{\"policy\":\"KEEP\"}")));
        assertThat(style(after).path("verdict").asText()).isEqualTo("KEEP");
    }

    @Test
    void ignoreSticksAcrossRelearn() throws Exception {
        setup();
        long id = style(body(mvc.perform(post("/api/games/{g}/rules/relearn", gameId)))).path("id").asLong();
        mvc.perform(post("/api/rules/{id}/ignore", id)).andExpect(status().isOk());
        assertThat(style(body(mvc.perform(post("/api/games/{g}/rules/relearn", gameId)))).path("status").asText()).isEqualTo("IGNORED");
        mvc.perform(post("/api/rules/{id}/unignore", id)).andExpect(status().isOk());
        assertThat(style(body(mvc.perform(get("/api/games/{g}/rules", gameId)))).path("status").asText()).isEqualTo("SUGGESTED");
    }

    @Test
    void relearnDropsNoticeRules() throws Exception {
        setup();
        CorrectionRule n = new CorrectionRule();
        n.setGameId(gameId); n.setType("STYLE"); n.setOfText("서술식"); n.setNfText("개조식"); n.setTextKind('N'); n.setStatus("APPROVED");
        rules.save(n);
        JsonNode list = body(mvc.perform(post("/api/games/{g}/rules/relearn", gameId)));
        for (JsonNode r : list.path("rules")) assertThat(r.path("textKind").asText()).isNotEqualTo("N");
    }

    @Test
    void relearnDropsOldWordRules() throws Exception {
        setup();
        CorrectionRule w = new CorrectionRule();
        w.setGameId(gameId); w.setType("WORD"); w.setOfText("심판"); w.setNfText("재판"); w.setStatus("APPROVED");
        rules.save(w);
        JsonNode list = body(mvc.perform(post("/api/games/{g}/rules/relearn", gameId)));
        for (JsonNode r : list.path("rules")) assertThat(r.path("type").asText()).isNotEqualTo("WORD");
    }
}
