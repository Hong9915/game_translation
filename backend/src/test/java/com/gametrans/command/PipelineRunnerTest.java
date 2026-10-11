package com.gametrans.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.gametrans.FakeJosaClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.josa.JosaHint;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** PipelineRunner: 시트 행 읽기(확정 포함, 제외·용어 빠짐), 사이드카 연결, 태그 짝 검사. */
class PipelineRunnerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired RevisionService revisions;
    @Autowired FakeJosaClient josa;
    @Autowired PipelineRunner runner;
    long pid;

    @BeforeEach
    void setUp() throws Exception {
        josa.reset();
        pid = TestProjects.uploadVariants(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE));
    }

    private Segment set(String src, String ko) {
        Segment s = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(x -> x.getSrcText().equals(src)).findFirst().orElseThrow();
        revisions.add(s.getId(), ko, TranslationRevision.Author.HUMAN);
        return s;
    }

    @Test
    void rowsIncludeConfirmedWithTheirFlags() throws Exception {
        Segment a = set("陷阱伤害", "피해 - 10");
        Segment b = set("放置陷阱", "확정 - 행");
        mvc.perform(put("/api/segments/{id}/marks", b.getId()).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"));
        List<SheetRow> rows = runner.rows(pid, "Skill");
        assertThat(rows).filteredOn(r -> r.id().equals(a.getId())).singleElement().satisfies(r -> {
            assertThat(r.ko()).isEqualTo("피해 - 10");
            assertThat(r.confirmed()).isFalse();
            assertThat(r.src()).isEqualTo("陷阱伤害");
        });
        assertThat(rows).filteredOn(r -> r.id().equals(b.getId())).singleElement().extracting(SheetRow::confirmed).isEqualTo(true);
    }

    @Test
    void sidecarCallsJosaWithFilteringAndReportsDownAsConflict() {
        josa.answers.put("구미호이", List.of(new JosaHint(0, "구미호이", "구미호가", "HANGUL", null)));
        Pipeline.Sidecar sc = runner.sidecar(runner.gameId(pid));
        assertThat(sc.josa(List.of("구미호이"))).containsExactly(List.of(new JosaHint(0, "구미호이", "구미호가", "HANGUL", null)));
        josa.failing = true;
        assertThatThrownBy(() -> sc.josa(List.of("x"))).isInstanceOf(IllegalStateException.class).hasMessage(PipelineRunner.SIDECAR_DOWN);
        assertThatThrownBy(() -> sc.tagSpace(List.of("x"))).isInstanceOf(IllegalStateException.class).hasMessage(PipelineRunner.SIDECAR_DOWN);
    }

    @Test
    void tagMismatchListsTokensMissingOnEitherSide() {
        List<SheetRow> rows = List.of(new SheetRow(1L, 1, "造成{0}点<color=red>伤害</color>", "피해 <color=red>{1}</color>", false, true, null, false),
                new SheetRow(2L, 2, "{0}点", "{0}점", false, true, null, false));
        List<PipelineRunner.TagMismatch> out = runner.tagMismatches(rows);
        assertThat(out).singleElement().satisfies(m -> {
            assertThat(m.row().id()).isEqualTo(1L);
            assertThat(m.srcOnly()).containsExactly("{0}");
            assertThat(m.koOnly()).containsExactly("{1}");
        });
    }
}
