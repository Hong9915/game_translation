package com.gametrans.review;

import static org.assertj.core.api.Assertions.assertThat;
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

/** 셀 하나를 고치면 게임 안에서 원문이 똑같은 다른 행도 같이 바뀐다. */
class DuplicateSyncTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired RevisionService revisions;

    private long normalized(String game) throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));
        return pid;
    }

    /** withExistingKoSameSourceTwice(): 같은 시트 안에 2행과 6행의 원문이 똑같다. */
    private long normalizedSameSourceTwice(String game) throws Exception {
        long pid = TestProjects.uploadExistingKoSameSourceTwice(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));
        return pid;
    }

    private Segment row(long pid, int rowNo) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getRowNo() == rowNo).findFirst().orElseThrow();
    }

    private void save(long sid, String text) throws Exception {
        mvc.perform(put("/api/segments/{id}/translation", sid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}")).andExpect(status().isOk());
    }

    @Test
    void theSameSourceInAnotherFileFollows() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalized(game);
        long b = normalized(game);

        save(row(a, 2).getId(), "정의의 판결");

        assertThat(revisions.current(row(b, 2).getId()).orElseThrow().getText()).isEqualTo("정의의 판결");
    }

    @Test
    void theResponseReportsHowManyFollowed() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalized(game);
        normalized(game);

        mvc.perform(put("/api/segments/{id}/translation", row(a, 2).getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"정의의 판결\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propagated").value(1))
                .andExpect(jsonPath("$.row.currentText").value("정의의 판결"));
    }

    @Test
    void confirmedRowsAreNotTouched() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalized(game);
        long b = normalized(game);
        Segment other = row(b, 2);
        mvc.perform(put("/api/segments/{id}/marks", other.getId()).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"));

        save(row(a, 2).getId(), "정의의 판결");

        assertThat(revisions.current(other.getId()).orElseThrow().getText()).isEqualTo("정의의 심판");
    }

    @Test
    void anotherGameIsNotTouched() throws Exception {
        long a = normalized(TestProjects.freshGame());
        long b = normalized(TestProjects.freshGame());

        save(row(a, 2).getId(), "정의의 판결");

        assertThat(revisions.current(row(b, 2).getId()).orElseThrow().getText()).isEqualTo("정의의 심판");
    }

    @Test
    void followedRowsGetAHumanRevision() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalized(game);
        long b = normalized(game);

        save(row(a, 2).getId(), "정의의 판결");

        List<TranslationRevision> h = revisions.bySegmentIds(List.of(row(b, 2).getId())).get(row(b, 2).getId());
        assertThat(h.get(h.size() - 1).getAuthor()).isEqualTo(TranslationRevision.Author.HUMAN);
    }

    /** 같은 게임의 파일이 셋이면 나머지 둘 다 따라가고, propagated는 정확히 그 수(2)를 보고한다. */
    @Test
    void propagationAcrossThreeFilesReportsExactCountAndEveryFollowerGetsTheText() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalized(game);
        long b = normalized(game);
        long c = normalized(game);

        mvc.perform(put("/api/segments/{id}/translation", row(a, 2).getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"정의의 판결\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propagated").value(2));

        assertThat(revisions.current(row(b, 2).getId()).orElseThrow().getText()).isEqualTo("정의의 판결");
        assertThat(revisions.current(row(c, 2).getId()).orElseThrow().getText()).isEqualTo("정의의 판결");
    }

    /** 한 파일(시트) 안에서도 원문이 같은 다른 행이 있으면 같이 바뀐다 — 게임을 건널 필요가 없다. */
    @Test
    void twoRowsInTheSameFileFollowEachOther() throws Exception {
        long pid = normalizedSameSourceTwice(TestProjects.freshGame());
        Segment first = row(pid, 2);
        Segment second = row(pid, 6);
        assertThat(second.getSrcText()).isEqualTo(first.getSrcText());

        save(first.getId(), "정의의 판결");

        assertThat(revisions.current(second.getId()).orElseThrow().getText()).isEqualTo("정의의 판결");
    }
}
