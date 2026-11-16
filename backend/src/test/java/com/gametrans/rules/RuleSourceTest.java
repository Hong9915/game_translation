package com.gametrans.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.ProjectRepository;
import com.gametrans.review.ReviewService;
import com.gametrans.rules.RuleLearner.Edit;
import com.gametrans.rules.RuleLearner.Row;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class RuleSourceTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired RuleSource source;
    @Autowired ReviewService reviews;
    @Autowired SegmentRepository segments;
    @Autowired ProjectRepository projects;
    @Autowired JdbcTemplate jdbc;

    private long pid;
    private Long gameId;

    private void setup() throws Exception {
        pid = TestProjects.uploadExistingKoSameSourceTwice(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));
        gameId = projects.findById(pid).orElseThrow().getGame().getId();
    }

    private Segment row(int rowNo) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getRowNo() == rowNo).findFirst().orElseThrow();
    }

    @Test
    void oneSaveIsOneEditEvenWithSameSourceCopies() throws Exception {
        setup();
        reviews.saveHuman(row(2).getId(), "정의의 재판", null, null);
        assertThat(row(6).getLatestKo()).isEqualTo("정의의 재판");   // 복사본도 바뀌었다
        List<Edit> es = source.edits(gameId);
        assertThat(es).singleElement().satisfies(e -> {
            assertThat(e.segmentId()).isEqualTo(row(2).getId());
            assertThat(e.before()).isEqualTo("정의의 심판");
            assertThat(e.after()).isEqualTo("정의의 재판");
            assertThat(e.src()).isEqualTo("正义裁决");
        });
    }

    @Test
    void machineActivitiesAreNotEdits() throws Exception {
        setup();
        long sid = row(2).getId();
        Long act = jdbc.queryForObject("insert into activity(game_id, project_id, kind, detail, created_at) values (?, ?, 'COMMAND_FIX', '{}'::jsonb, now()) returning id",
                Long.class, gameId, pid);
        jdbc.update("insert into translation_revision(segment_id, version, text, author, created_at, activity_id) " +
                "select ?, max(version) + 1, '바뀐 글', 'HUMAN', now(), ? from translation_revision where segment_id = ?", sid, act, sid);
        assertThat(source.edits(gameId)).isEmpty();
    }

    @Test
    void confirmedRowsAreLeftOut() throws Exception {
        setup();
        long sid = row(2).getId();
        jdbc.update("update segment set confirmed = true where id = ?", sid);
        List<Row> rs = source.rows(gameId);
        assertThat(rs).isNotEmpty().noneMatch(r -> r.segmentId() == sid);
        assertThat(rs).anyMatch(r -> "정의의 심판".equals(r.ko()) && r.projectId() == pid);
    }
}
