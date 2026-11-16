package com.gametrans.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.llm.LlmClient;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.ProjectRepository;
import com.gametrans.review.ReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class StyleRewriteTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ReviewService reviews;
    @Autowired SegmentRepository segments;
    @Autowired ProjectRepository projects;
    @Autowired CorrectionRuleRepository rules;
    @Autowired LlmClient llm;

    @Test
    void acceptsOnlyEndingChanges() {
        assertThat(StyleRewrite.acceptable("버튼을 누르십시오", "버튼 누르기")).isFalse();
        assertThat(StyleRewrite.acceptable("<b>보상</b>을 받으십시오", "보상을 받음")).isFalse();
        assertThat(StyleRewrite.acceptable("3회 사용하십시오", "5회 사용")).isFalse();
        assertThat(StyleRewrite.acceptable("3회 사용하십시오", "3회 사용")).isTrue();
        assertThat(StyleRewrite.acceptable("3회 사용하십시오", "3회 사용하십시오")).isFalse();
    }

    @Test
    void endpointDropsRejectedRows() throws Exception {
        long pid = TestProjects.uploadExistingKoSameSourceTwice(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));
        Long gameId = projects.findById(pid).orElseThrow().getGame().getId();
        java.util.function.IntFunction<Segment> row = n -> segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid)
                .stream().filter(s -> s.getRowNo() == n).findFirst().orElseThrow();
        reviews.saveHuman(row.apply(2).getId(), "3회 사용하십시오", null, null);
        reviews.saveHuman(row.apply(4).getId(), "버튼을 누르십시오", null, null);
        FakeLlmClient fake = (FakeLlmClient) llm;
        fake.styleRewrites.put("3회 사용하십시오", "3회 사용");
        fake.styleRewrites.put("버튼을 누르십시오", "버튼 누르기");
        CorrectionRule r = new CorrectionRule();
        r.setGameId(gameId); r.setType("STYLE"); r.setOfText("서술식"); r.setNfText("개조식"); r.setTextKind('E');
        r = rules.save(r);
        mvc.perform(post("/api/rules/{id}/rewrite", r.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segmentIds\":[" + row.apply(2).getId() + "," + row.apply(4).getId() + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].after").value("3회 사용"))
                .andExpect(jsonPath("$[0].checked").value(true));
    }
}
