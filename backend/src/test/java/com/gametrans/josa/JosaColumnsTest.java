package com.gametrans.josa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** V20: latest_ko가 바뀌면 검사 상태가 초기화된다(설계 2026-10-07 §1). */
class JosaColumnsTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired RevisionService revisions;
    @Autowired JdbcTemplate jdbc;

    Segment firstRow() throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE))
                .andExpect(status().isOk());
        return segments.findByProjectIdAndSheetNameAndSkippedFalseOrderByRowNoAscPairIndexAsc(pid, "Skill").get(0);
    }

    void markChecked(Long id) {
        jdbc.update("update segment set josa_checked = true, josa_hint = '[{\"at\":0,\"from\":\"생명를\",\"to\":\"생명을\",\"kind\":\"HANGUL\"}]'::jsonb, josa_ignored = true where id = ?", id);
    }

    @Test
    void newRowsStartNotRequested() throws Exception {
        // 아무도 열지 않았고 번역도 안 바뀐 행은 검사 대상이 아니다(null) — 2026-10-07 사용자 결정
        Segment s = segments.findById(firstRow().getId()).orElseThrow();
        assertThat(s.getJosaChecked()).isNull();
        assertThat(s.getJosaHint()).isNull();
        assertThat(s.isJosaIgnored()).isFalse();
    }

    @Test
    void triggerResetsOnLatestKoChange() throws Exception {
        Long id = firstRow().getId();
        markChecked(id);
        revisions.add(id, "아군의 생명를 회복", TranslationRevision.Author.HUMAN);
        Segment s = segments.findById(id).orElseThrow();
        assertThat(s.getJosaChecked()).isFalse();
        assertThat(s.getJosaHint()).isNull();
        assertThat(s.isJosaIgnored()).isFalse();
    }

    @Test
    void sameValueUpdateKeepsState() throws Exception {
        Long id = firstRow().getId();
        markChecked(id);
        jdbc.update("update segment set latest_ko = latest_ko where id = ?", id);
        Segment s = segments.findById(id).orElseThrow();
        assertThat(s.getJosaChecked()).isTrue();
        assertThat(s.getJosaHint()).contains("생명을");
        assertThat(s.isJosaIgnored()).isTrue();
    }

    @Test
    void saveJosaOnlyWhenTextUnchanged() throws Exception {
        Long id = firstRow().getId();
        revisions.add(id, "아군의 생명를 회복", TranslationRevision.Author.HUMAN);
        assertThat(segments.saveJosa(id, "다른 글", "[]")).isZero();
        assertThat(segments.saveJosa(id, "아군의 생명를 회복", "[{\"at\":4,\"from\":\"생명를\",\"to\":\"생명을\",\"kind\":\"HANGUL\"}]")).isEqualTo(1);
        Segment s = segments.findById(id).orElseThrow();
        assertThat(s.getJosaChecked()).isTrue();
        assertThat(s.getJosaHint()).contains("생명을");
    }
}
