package com.gametrans.josa;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** 조사 확인 필터·행 제안·요약 건수(설계 2026-10-07 §5). */
class JosaWorkspaceTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired JdbcTemplate jdbc;

    static final String HINT = "[{\"at\":0,\"from\":\"생명를\",\"to\":\"생명을\",\"kind\":\"HANGUL\"}]";

    long project() throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE))
                .andExpect(status().isOk());
        return pid;
    }

    List<Segment> rows(long pid) { return segments.findByProjectIdAndSheetNameAndSkippedFalseOrderByRowNoAscPairIndexAsc(pid, "Skill"); }

    @Test
    void filterListsRowsWithHintsAndNotIgnored() throws Exception {
        long pid = project();
        List<Segment> rs = rows(pid);
        jdbc.update("update segment set josa_checked = true, josa_hint = ?::jsonb where id = ?", HINT, rs.get(0).getId());
        jdbc.update("update segment set josa_checked = true, josa_hint = ?::jsonb, josa_ignored = true where id = ?", HINT, rs.get(1).getId());

        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("filter", "JOSA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].id").value(rs.get(0).getId()))
                .andExpect(jsonPath("$.rows[0].josaHints[0].to").value("생명을"))
                .andExpect(jsonPath("$.rows[0].josaHints[0].dictNoun").doesNotExist());
    }

    @Test
    void ignoredRowsShowNoHintsInAllView() throws Exception {
        long pid = project();
        Segment s = rows(pid).get(0);
        jdbc.update("update segment set josa_checked = true, josa_hint = ?::jsonb, josa_ignored = true where id = ?", HINT, s.getId());
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill"))
                .andExpect(jsonPath("$.rows[0].josaHints").isEmpty());
    }

    @Test
    void summaryCountsJosa() throws Exception {
        long pid = project();
        jdbc.update("update segment set josa_checked = true, josa_hint = ?::jsonb where id = ?", HINT, rows(pid).get(0).getId());
        mvc.perform(get("/api/review/{id}/rows/summary", pid).param("sheet", "Skill"))
                .andExpect(jsonPath("$.josa").value(1))
                .andExpect(jsonPath("$.sheets[0].josa").value(1));
    }
}
