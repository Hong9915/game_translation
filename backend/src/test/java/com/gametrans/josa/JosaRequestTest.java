package com.gametrans.josa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 검사 대상은 누군가 연 파일과 번역이 바뀐 행뿐이다(2026-10-07 사용자 결정). */
class JosaRequestTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired RevisionService revisions;

    long project() throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE))
                .andExpect(status().isOk());
        return pid;
    }

    List<Segment> rows(long pid) { return segments.findByProjectIdAndSheetNameAndSkippedFalseOrderByRowNoAscPairIndexAsc(pid, "Skill"); }

    boolean anyPending(long pid) { return segments.josaPending(100_000).stream().anyMatch(p -> rows(pid).stream().anyMatch(r -> r.getId().equals(p.getId()))); }

    @Test
    void unopenedFileIsNotChecked() throws Exception {
        long pid = project();
        assertThat(anyPending(pid)).isFalse();
    }

    @Test
    void openingTheFileQueuesItsRowsAndShowsPending() throws Exception {
        long pid = project();
        int nonBlank = (int) rows(pid).stream().filter(r -> r.getLatestKo() != null && !r.getLatestKo().isBlank()).count();
        mvc.perform(get("/api/review/{id}/rows/summary", pid).param("sheet", "Skill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.josaPending").value(nonBlank))
                .andExpect(jsonPath("$.sheets[0].josaPending").value(nonBlank));
        assertThat(anyPending(pid)).isTrue();
    }

    @Test
    void editedRowInUnopenedFileIsChecked() throws Exception {
        long pid = project();
        Long id = rows(pid).get(0).getId();
        revisions.add(id, "아군의 생명를 회복", TranslationRevision.Author.HUMAN);
        assertThat(segments.findById(id).orElseThrow().getJosaChecked()).isFalse();
        assertThat(rows(pid).stream().skip(1).map(Segment::getJosaChecked)).containsOnlyNulls();
    }
}
