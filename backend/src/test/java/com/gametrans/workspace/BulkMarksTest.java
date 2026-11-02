package com.gametrans.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 머리글 더블클릭 일괄 체크(설계 2026-09-28 §3): 대상은 목록 API와 같은 조건으로 고른 행이다. */
class BulkMarksTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;

    private long normalized(String game) throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE))
                .andExpect(status().isOk());
        return pid;
    }

    private List<Segment> rows(long pid) {
        return segments.findByProjectIdAndSheetNameAndSkippedFalseOrderByRowNoAscPairIndexAsc(pid, "Skill");
    }

    private Set<Long> listed(long pid, String filter) throws Exception {
        String body = mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("filter", filter))
                .andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.parse(body).read("$.rows[*].id");
        Set<Long> out = new HashSet<>();
        for (Number n : ids) out.add(n.longValue());
        return out;
    }

    private org.springframework.test.web.servlet.ResultActions bulk(long pid, String json) throws Exception {
        return mvc.perform(post("/api/review/{id}/marks", pid).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void pageScopeTouchesOnlyThatPage() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        bulk(pid, "{\"sheet\":\"Skill\",\"page\":1,\"size\":2,\"review1\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(2));

        List<Segment> all = rows(pid);
        assertThat(all).hasSize(4);
        assertThat(all.stream().map(Segment::isReview1).toList()).containsExactly(false, false, true, true);
        assertThat(all.stream().map(Segment::isReview2).toList()).containsOnly(false);   // 1차와 2차는 따로다
        assertThat(all.stream().map(Segment::isNeedsCheck).toList()).containsOnly(false);
    }

    @Test
    void searchScopesTheWholeView() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        bulk(pid, "{\"sheet\":\"Skill\",\"q\":\"伤害\",\"needsCheck\":true}").andExpect(status().isOk());

        for (Segment s : rows(pid)) assertThat(s.isNeedsCheck()).as(s.getSrcText()).isEqualTo(s.getSrcText().contains("伤害"));
        // 해제도 된다
        bulk(pid, "{\"sheet\":\"Skill\",\"needsCheck\":false}").andExpect(jsonPath("$.updated").value(4));
        assertThat(rows(pid).stream().map(Segment::isNeedsCheck).toList()).containsOnly(false);
    }

    @Test
    void searchInExistingKoScopesTheWholeView() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        String word = rows(pid).stream().map(Segment::getExistingKoText).filter(t -> t != null && t.length() >= 2).findFirst().orElseThrow().substring(0, 2);
        bulk(pid, "{\"sheet\":\"Skill\",\"q\":\"" + word + "\",\"qIn\":\"EXISTING_KO\",\"needsCheck\":true}").andExpect(status().isOk());

        for (Segment s : rows(pid)) assertThat(s.isNeedsCheck()).as(s.getSrcText())
                .isEqualTo(s.getExistingKoText() != null && s.getExistingKoText().contains(word));
        // 원문에 있는 말은 기존 한국어 검색에 안 걸린다
        bulk(pid, "{\"sheet\":\"Skill\",\"q\":\"伤害\",\"qIn\":\"EXISTING_KO\",\"review1\":true}").andExpect(jsonPath("$.updated").value(0));
    }

    @Test
    void dupConflictFilterMatchesTheList() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalized(game);
        long b = normalized(game);
        Long other = rows(b).stream().filter(s -> "正义裁决".equals(s.getSrcText())).findFirst().orElseThrow().getId();
        Long mine = rows(a).stream().filter(s -> "正义裁决".equals(s.getSrcText())).findFirst().orElseThrow().getId();
        mvc.perform(put("/api/segments/{id}/marks", other).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"));
        mvc.perform(put("/api/segments/{id}/translation", mine).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"정의의 판결\"}"));
        Set<Long> expected = listed(a, "DUP_CONFLICT");
        assertThat(expected).containsExactly(mine);

        bulk(a, "{\"sheet\":\"Skill\",\"filter\":\"DUP_CONFLICT\",\"review1\":true}").andExpect(jsonPath("$.updated").value(1));
        for (Segment s : rows(a)) assertThat(s.isReview1()).isEqualTo(s.getId().equals(mine));
    }

    @Test
    void idsScopeTouchesExactlyThoseRowsOfThatSheet() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        long other = normalized(TestProjects.freshGame());
        List<Segment> all = rows(pid);
        Long foreign = rows(other).get(0).getId();   // 다른 프로젝트의 id는 무시한다
        bulk(pid, "{\"sheet\":\"Skill\",\"ids\":[" + all.get(1).getId() + "," + all.get(3).getId() + "," + foreign + "],\"review1\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(2));
        assertThat(rows(pid).stream().map(Segment::isReview1).toList()).containsExactly(false, true, false, true);
        assertThat(rows(other).get(0).isReview1()).isFalse();
    }

    @Test
    void requiresSheetAndAMark() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        bulk(pid, "{\"review1\":true}").andExpect(status().isBadRequest());
        bulk(pid, "{\"sheet\":\"Skill\"}").andExpect(status().isBadRequest());
    }
}
