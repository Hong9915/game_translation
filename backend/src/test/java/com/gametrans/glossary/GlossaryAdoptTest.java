package com.gametrans.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.common.NotFoundException;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.ProjectRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class GlossaryAdoptTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired SegmentRepository segments;
    @Autowired GlossaryService service;
    @Autowired GlossaryEntryRepository entries;

    long pid;
    String gameName;

    Long game() throws Exception {
        gameName = TestProjects.freshGame();
        pid = TestProjects.uploadVariants(mvc, gameName);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE));
        return projects.findById(pid).orElseThrow().getGame().getId();
    }

    private Segment row(String src) {
        return row(pid, src);
    }

    private Segment row(long projectId, String src) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(projectId).stream()
                .filter(s -> s.getSrcText().equals(src)).findFirst().orElseThrow();
    }

    @Test
    void replacingAllOthersSavesTranslationEntry() throws Exception {
        Long g = game();
        GlossaryDtos.SaveResult r = service.adopt(g, "陷阱", "함정", List.of("트랩"), List.of(), null);
        assertThat(r.applied()).isEqualTo(2);
        assertThat(r.entry().kind()).isEqualTo("SRC");
        assertThat(r.entry().matchMode()).isEqualTo("PARTIAL");
        assertThat(r.entry().find()).isEqualTo("陷阱");
        assertThat(r.entry().ko()).isEqualTo("함정");
        assertThat(r.entry().note()).isNull();
        assertThat(r.entry().oldKo()).isNull();
        assertThat(row("陷阱伤害").getLatestKo()).isEqualTo("함정 피해");
        assertThat(row("陷阱强化").getLatestKo()).isEqualTo("함정 강화");
    }

    @Test
    void keepingSomeSavesNoteOnly() throws Exception {
        Long g = game();
        GlossaryDtos.SaveResult r = service.adopt(g, "每日", "매일", List.of("일일"), List.of("하루에"), null);
        assertThat(r.entry().ko()).isNull();
        assertThat(r.entry().note()).isEqualTo("매일로 통일, 하루에도 씀");
        assertThat(r.entry().oldKo()).isNull();
        assertThat(row("每日任务").getLatestKo()).isEqualTo("매일 임무");
        assertThat(row("每日刷新").getLatestKo()).isEqualTo("하루에 갱신");
    }

    @Test
    void overwritesExistingEntry() throws Exception {
        Long g = game();
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"SRC\",\"matchMode\":\"PARTIAL\",\"find\":\"陷阱\",\"ko\":\"트랩\"}")).andExpect(status().isOk());
        Long before = entries.findByGameIdAndKindAndFind(g, GlossaryEntry.Kind.SRC, "陷阱").orElseThrow().getId();
        GlossaryDtos.SaveResult r = service.adopt(g, "陷阱", "함정", List.of(), List.of(), null);
        assertThat(r.entry().id()).isEqualTo(before);
        assertThat(r.entry().ko()).isEqualTo("함정");
        assertThat(r.applied()).isZero();
        assertThat(entries.findByGameId(g)).hasSize(1);
    }

    @Test
    void singleCharUsesWholeMatch() throws Exception {
        Long g = game();
        long coins = TestProjects.uploadWholeTerm(mvc, gameName);
        mvc.perform(put("/api/projects/{id}/normalize", coins).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE))
                .andExpect(status().isOk());
        assertThat(projects.findById(coins).orElseThrow().getGame().getId()).isEqualTo(g);

        GlossaryDtos.SaveResult r = service.adopt(g, "金", "골드", List.of(), List.of(), null);
        assertThat(r.entry().matchMode()).isEqualTo("WHOLE");
        assertThat(r.entry().ko()).isEqualTo("골드");
        assertThat(r.applied()).isGreaterThanOrEqualTo(1);
        assertThat(row(coins, "金").getLatestKo()).isEqualTo("골드");
        assertThat(row(coins, "金币商店").getLatestKo()).isEqualTo("금화 상점");
    }

    @Test
    void blankKoIsRejected() throws Exception {
        Long g = game();
        assertThatThrownBy(() -> service.adopt(g, "陷阱", "  ", List.of("트랩"), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("최종 표기를 입력하세요.");
        assertThat(row("陷阱伤害").getLatestKo()).isEqualTo("트랩 피해");
    }

    @Test
    void unknownGameIsNotFound() {
        assertThatThrownBy(() -> service.adopt(999999L, "陷阱", "함정", List.of(), List.of(), null))
                .isInstanceOf(NotFoundException.class);
    }
}
