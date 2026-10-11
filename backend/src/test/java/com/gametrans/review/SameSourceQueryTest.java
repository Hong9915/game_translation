package com.gametrans.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.game.Game;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.ProjectRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 같은 게임 안에서 원문이 글자까지 같은 행을 찾는 조회. */
class SameSourceQueryTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired ProjectRepository projects;

    private long normalized(String game) throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));
        return pid;
    }

    private Long gameId(long projectId) { return projects.findById(projectId).orElseThrow().getGame().getId(); }

    private Segment row(long pid, int rowNo) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getRowNo() == rowNo).findFirst().orElseThrow();
    }

    @Test
    void findsTheSameSourceInAnotherProjectOfTheSameGame() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalized(game);
        long b = normalized(game);
        Segment mine = row(a, 2); // 正义裁决

        List<Segment> same = segments.findSameSourceInGame(gameId(a), mine.getSrcText(), mine.getId());

        assertThat(same).hasSize(1);
        assertThat(same.get(0).getProjectId()).isEqualTo(b);
        assertThat(same.get(0).getSrcText()).isEqualTo("正义裁决");
    }

    @Test
    void doesNotCrossGames() throws Exception {
        long a = normalized(TestProjects.freshGame());
        normalized(TestProjects.freshGame());
        Segment mine = row(a, 2);

        assertThat(segments.findSameSourceInGame(gameId(a), mine.getSrcText(), mine.getId())).isEmpty();
    }

    @Test
    void skipsReview2Rows() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalized(game);
        long b = normalized(game);
        Segment mine = row(a, 2);
        Segment other = row(b, 2);

        mvc.perform(put("/api/segments/{id}/marks", other.getId()).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"));

        assertThat(segments.findSameSourceInGame(gameId(a), mine.getSrcText(), mine.getId())).isEmpty();
    }
}
