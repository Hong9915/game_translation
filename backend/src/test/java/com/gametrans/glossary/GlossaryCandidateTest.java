package com.gametrans.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.job.JobEventBus;
import com.gametrans.review.ReviewEvents;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** 단어장 후보(승인 대기): 기록·합치기·승인(행 안 고침)·버림·SSE 이름. */
class GlossaryCandidateTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired GlossaryCandidateService candidates;
    @SpyBean JobEventBus bus;

    long pid, gameId, me, other;

    @BeforeEach
    void setUp() throws Exception {
        pid = TestProjects.uploadVariants(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE));
        gameId = jdbc.queryForObject("select game_id from project where id = ?", Long.class, pid);
        me = newReviewer();
        other = newReviewer();
    }

    long newReviewer() throws Exception {
        String body = mvc.perform(post("/api/reviewers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"후보검수자-" + java.util.UUID.randomUUID().toString().substring(0, 8) + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body).read("$.id", Long.class);
    }

    void entry(String find, String ko, String note, String oldKo) {
        jdbc.update("insert into glossary (game_id, kind, match_mode, find, ko, note, old_ko) values (?, 'SRC', 'PARTIAL', ?, ?, ?, ?)",
                gameId, find, ko, note, oldKo);
    }

    void rec(String ko, String oldKo, int rows, String reason, long by) {
        candidates.record(gameId, "陷阱", ko, oldKo, rows, reason, "CONNECTOR", null, by);
    }

    String list() throws Exception {
        return mvc.perform(get("/api/games/{id}/glossary/candidates", gameId)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    long onlyId() throws Exception { return JsonPath.parse(list()).read("$[0].id", Long.class); }

    @Test
    void recordThenListShowsPendingWithConflict() throws Exception {
        entry("陷阱", "함정", null, null);
        rec("올가미", "함정", 4, "고객 요청", me);
        mvc.perform(get("/api/games/{id}/glossary/candidates", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].src").value("陷阱"))
                .andExpect(jsonPath("$[0].ko").value("올가미"))
                .andExpect(jsonPath("$[0].oldKo").value("함정"))
                .andExpect(jsonPath("$[0].rows").value(4))
                .andExpect(jsonPath("$[0].source").value("CONNECTOR"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].conflictKo").value("함정"))
                .andExpect(jsonPath("$[0].createdByName").value(jdbc.queryForObject("select name from reviewer where id = ?", String.class, me)));
    }

    @Test
    void sameDecisionMergesRows() throws Exception {
        rec("올가미", "함정", 4, "a", me);
        rec("올가미", "함정", 2, "b", other);
        mvc.perform(get("/api/games/{id}/glossary/candidates", gameId))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].rows").value(6))
                .andExpect(jsonPath("$[0].createdBy").value(other))
                .andExpect(jsonPath("$[0].reason").value("b"));
    }

    @Test
    void noCandidateWhenGlossaryAlreadySame() throws Exception {
        entry("陷阱", "올가미", null, null);
        rec("올가미", "함정", 4, null, me);
        mvc.perform(get("/api/games/{id}/glossary/candidates", gameId)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void recordTrimsSrcAndKoSoApproveWorks() throws Exception {
        candidates.record(gameId, " 陷阱 ", " 올가미 ", null, 3, null, "CONNECTOR", null, me);
        String body = mvc.perform(get("/api/games/{id}/glossary/candidates", gameId)).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(JsonPath.parse(body).read("$[0].src", String.class)).isEqualTo("陷阱");
        assertThat(JsonPath.parse(body).read("$[0].ko", String.class)).isEqualTo("올가미");
        mvc.perform(post("/api/glossary/candidates/{id}/approve", onlyId()).header("X-Reviewer-Id", other)).andExpect(status().isOk())
                .andExpect(jsonPath("$.entry.find").value("陷阱"));
    }

    @Test
    void zeroRowsRecordsNothing() throws Exception {
        rec("올가미", "함정", 0, null, me);
        mvc.perform(get("/api/games/{id}/glossary/candidates", gameId)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void approveOverwritesKoClearsOldKoKeepsNoteAndRowsUntouched() throws Exception {
        entry("陷阱", "함정", "옛 메모", "함정X");
        rec("올가미", "함정", 4, null, me);
        List<Object> before = jdbc.queryForList("select id || ':' || coalesce(latest_ko, '') from segment order by id", Object.class);
        long revs = jdbc.queryForObject("select count(*) from translation_revision", Long.class);
        mvc.perform(post("/api/glossary/candidates/{id}/approve", onlyId()).header("X-Reviewer-Id", other)).andExpect(status().isOk())
                .andExpect(jsonPath("$.candidate.status").value("APPROVED"))
                .andExpect(jsonPath("$.entry.ko").value("올가미"));
        var row = jdbc.queryForMap("select ko, old_ko, note, match_mode from glossary where game_id = ? and kind = 'SRC' and find = '陷阱'", gameId);
        assertThat(row.get("ko")).isEqualTo("올가미");
        assertThat(row.get("old_ko")).isNull();
        assertThat(row.get("note")).isEqualTo("옛 메모");
        assertThat(row.get("match_mode")).isEqualTo("PARTIAL");
        assertThat(jdbc.queryForList("select id || ':' || coalesce(latest_ko, '') from segment order by id", Object.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from translation_revision", Long.class)).isEqualTo(revs);
    }

    @Test
    void approvingOneClosesOtherSameSrcAndSecondApproveIs409() throws Exception {
        rec("올가미", null, 3, null, me);
        rec("덫", null, 2, null, other);
        long a = jdbc.queryForObject("select id from glossary_candidate where ko = '올가미' and game_id = ?", Long.class, gameId);
        long b = jdbc.queryForObject("select id from glossary_candidate where ko = '덫' and game_id = ?", Long.class, gameId);
        mvc.perform(post("/api/glossary/candidates/{id}/approve", a).header("X-Reviewer-Id", me)).andExpect(status().isOk())
                .andExpect(jsonPath("$.closed[0]").value(b));
        assertThat(jdbc.queryForObject("select status from glossary_candidate where id = ?", String.class, b)).isEqualTo("DISMISSED");
        assertThat(jdbc.queryForObject("select decided_by from glossary_candidate where id = ?", Long.class, b)).isEqualTo(me);
        mvc.perform(post("/api/glossary/candidates/{id}/approve", b).header("X-Reviewer-Id", other)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 처리된 후보입니다."))
                .andExpect(jsonPath("$.candidate.status").value("DISMISSED"));
        mvc.perform(post("/api/glossary/candidates/{id}/approve", 99999999L)).andExpect(status().isNotFound());
    }

    @Test
    void dismissKeepsGlossary() throws Exception {
        entry("陷阱", "함정", null, null);
        rec("올가미", "함정", 4, null, me);
        long id = onlyId();
        mvc.perform(post("/api/glossary/candidates/{id}/dismiss", id)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISMISSED"));
        assertThat(jdbc.queryForObject("select ko from glossary where game_id = ? and find = '陷阱'", String.class, gameId)).isEqualTo("함정");
        mvc.perform(post("/api/glossary/candidates/{id}/dismiss", id)).andExpect(status().isConflict());
    }

    @Test
    void eventsUseOwnSseName() throws Exception {
        rec("올가미", null, 3, null, me);
        verify(bus).publishGameEvent(eq(gameId), eq("glossary-candidate"), any(ReviewEvents.CandidateEvent.class));
        mvc.perform(post("/api/glossary/candidates/{id}/approve", onlyIdPending()).header("X-Reviewer-Id", me)).andExpect(status().isOk());
        verify(bus, org.mockito.Mockito.times(2)).publishGameEvent(eq(gameId), eq("glossary-candidate"), any(ReviewEvents.CandidateEvent.class));
        verify(bus, never()).publishGameEvent(eq(gameId), eq("review"), any(ReviewEvents.CandidateEvent.class));
        verify(bus, never()).publishProjectEvent(eq(pid), eq("review"), any(ReviewEvents.CandidateEvent.class));
    }

    long onlyIdPending() { return jdbc.queryForObject("select id from glossary_candidate where game_id = ?", Long.class, gameId); }
}
