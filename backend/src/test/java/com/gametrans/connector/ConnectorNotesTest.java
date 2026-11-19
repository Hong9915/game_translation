package com.gametrans.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 커넥터 시트 목록과 비고 붙이기: 비고·확인 필요만 쓰고 변경 내역(activity)은 남기지 않는다. */
class ConnectorNotesTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    long normalPid, finalPid, gameId, reviewerId;

    @BeforeEach
    void setUp() throws Exception {
        String game = TestProjects.freshGame();
        normalPid = TestProjects.uploadExistingKoSameSourceTwice(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", normalPid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE)).andExpect(status().isOk());
        finalPid = TestProjects.uploadExistingKoAsFinal(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", finalPid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.FINAL_SKILL_NORMALIZE)).andExpect(status().isOk());
        gameId = jdbc.queryForObject("select game_id from project where id = ?", Long.class, normalPid);
        String body = mvc.perform(post("/api/reviewers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"비고검수자-" + UUID.randomUUID().toString().substring(0, 8) + "\"}"))
                .andReturn().getResponse().getContentAsString();
        reviewerId = JsonPath.parse(body).read("$.id", Long.class);
    }

    long row(long pid, int rowNo) {
        return jdbc.queryForObject("select id from segment where project_id = ? and row_no = ? and skipped = false order by id limit 1",
                Long.class, pid, rowNo);
    }

    String note(long sid) { return jdbc.queryForObject("select note from segment where id = ?", String.class, sid); }

    void setNote(long sid, String note) { jdbc.update("update segment set note = ? where id = ?", note, sid); }

    ResultActions notes(String itemsJson) throws Exception {
        return mvc.perform(post("/api/connector/notes").header("X-Reviewer-Id", reviewerId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"items\":" + itemsJson + "}"));
    }

    @Test
    void sheetsListsBothLanes() throws Exception {
        mvc.perform(get("/api/connector/games/{id}/sheets", gameId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].projectId").value((int) normalPid))
                .andExpect(jsonPath("$[0].fileName").value("Skill_DB2.xlsx"))
                .andExpect(jsonPath("$[0].sheet").value("Skill"))
                .andExpect(jsonPath("$[0].finalReview").value(false))
                .andExpect(jsonPath("$[0].configId").isNumber())
                .andExpect(jsonPath("$[1].projectId").value((int) finalPid))
                .andExpect(jsonPath("$[1].fileName").value("Skill_final.xlsx"))
                .andExpect(jsonPath("$[1].finalReview").value(true));
    }

    @Test
    void appendsAfterExistingNote() throws Exception {
        long sid = row(normalPid, 2);
        setNote(sid, "원래");
        notes("[{\"segmentId\":" + sid + ",\"note\":\"고객 요청 11/13\"}]").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].segmentId").value((int) sid))
                .andExpect(jsonPath("$[0].before").value("원래"))
                .andExpect(jsonPath("$[0].after").value("원래\n고객 요청 11/13"));
        assertThat(note(sid)).isEqualTo("원래\n고객 요청 11/13");
    }

    @Test
    void appendToEmptyNoteHasNoLeadingNewline() throws Exception {
        long sid = row(normalPid, 3);
        notes("[{\"segmentId\":" + sid + ",\"note\":\"고객 요청\"}]").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].after").value("고객 요청"));
        assertThat(note(sid)).isEqualTo("고객 요청");
    }

    @Test
    void replaceOverwrites() throws Exception {
        long sid = row(normalPid, 2);
        setNote(sid, "원래");
        notes("[{\"segmentId\":" + sid + ",\"note\":\"새 값\",\"mode\":\"REPLACE\"}]").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].before").value("원래"))
                .andExpect(jsonPath("$[0].after").value("새 값"));
        assertThat(note(sid)).isEqualTo("새 값");
    }

    @Test
    void needsCheckOnlyLeavesNote() throws Exception {
        long sid = row(normalPid, 2);
        setNote(sid, "원래");
        notes("[{\"segmentId\":" + sid + ",\"needsCheck\":true}]").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].after").value("원래"))
                .andExpect(jsonPath("$[0].needsCheck").value(true));
        assertThat(note(sid)).isEqualTo("원래");
        assertThat(jdbc.queryForObject("select needs_check from segment where id = ?", Boolean.class, sid)).isTrue();
    }

    @Test
    void allowedOnConfirmedRow() throws Exception {
        long sid = row(normalPid, 2);
        jdbc.update("update segment set confirmed = true where id = ?", sid);
        notes("[{\"segmentId\":" + sid + ",\"note\":\"확정 뒤 요청\"}]").andExpect(status().isOk());
        assertThat(note(sid)).isEqualTo("확정 뒤 요청");
    }

    @Test
    void noActivityIsRecorded() throws Exception {
        long sid = row(normalPid, 2);
        long before = jdbc.queryForObject("select count(*) from activity", Long.class);
        notes("[{\"segmentId\":" + sid + ",\"note\":\"기록 없음\",\"needsCheck\":true}]").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from activity", Long.class)).isEqualTo(before);
    }

    @Test
    void rejectsOver500() throws Exception {
        long sid = row(normalPid, 2);
        String items = IntStream.range(0, 501).mapToObj(i -> "{\"segmentId\":" + sid + ",\"note\":\"x\"}")
                .collect(Collectors.joining(",", "[", "]"));
        notes(items).andExpect(status().isBadRequest());
        notes("[]").andExpect(status().isBadRequest());
        assertThat(note(sid)).isNull();
    }

    @Test
    void notesWithoutReviewerHeaderIs400() throws Exception {
        long sid = row(normalPid, 2);
        mvc.perform(post("/api/connector/notes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"segmentId\":" + sid + ",\"note\":\"x\",\"needsCheck\":true}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ConnectorSaveTest.NO_REVIEWER));
        assertThat(note(sid)).isNull();
        assertThat(jdbc.queryForObject("select needs_check from segment where id = ?", Boolean.class, sid)).isFalse();
    }

    @Test
    void notesWithDeletedReviewerIs400() throws Exception {
        long sid = row(normalPid, 2);
        mvc.perform(post("/api/connector/notes").header("X-Reviewer-Id", ConnectorSaveTest.deletedReviewer(mvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"segmentId\":" + sid + ",\"note\":\"x\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ConnectorSaveTest.NO_REVIEWER));
        assertThat(note(sid)).isNull();
    }

    @Test
    void unknownSegmentIs404() throws Exception {
        notes("[{\"segmentId\":999999999,\"note\":\"x\"}]").andExpect(status().isNotFound());
    }
}
