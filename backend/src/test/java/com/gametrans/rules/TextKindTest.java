package com.gametrans.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

class TextKindTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ReviewService reviews;
    @Autowired SegmentRepository segments;
    @Autowired ProjectRepository projects;
    @Autowired LlmClient llm;
    @Autowired TextKindService kinds;
    @Autowired CorrectionRuleService rules;

    long pid;
    Long gameId;

    Segment row(int rowNo) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getRowNo() == rowNo).findFirst().orElseThrow();
    }

    @Test
    void judgesOnlyFormalRowsOnceAndDropsBadCodes() throws Exception {
        FakeLlmClient fake = (FakeLlmClient) llm;
        pid = TestProjects.uploadExistingKoSameSourceTwice(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));
        gameId = projects.findById(pid).orElseThrow().getGame().getId();
        reviews.saveHuman(row(2).getId(), "탈퇴할 수 없습니다.", null, null);       // 6행에도 퍼진다
        reviews.saveHuman(row(4).getId(), "피해 감소가 증가합니다.", null, null);
        fake.textKinds.put("正义裁决", "E");
        fake.textKinds.put("伤害减免提升至@10%@", "X");
        fake.textKindCalls.clear();

        assertThat(rules.list(gameId).unjudgedRows()).isEqualTo(3);
        assertThat(kinds.countTargets(gameId)).isEqualTo(3);
        assertThat(rules.list(gameId).estimateUsd()).isPositive();

        mvc.perform(post("/api/games/{g}/rules/judge-kinds", gameId)).andExpect(status().isOk());
        assertThat(row(2).getTextKind()).isEqualTo('E');
        assertThat(row(6).getTextKind()).isEqualTo('E');
        assertThat(row(4).getTextKind()).isNull();
        assertThat(fake.textKindCalls).flatMap(b -> b).extracting(LlmClient.KindInput::zh).doesNotContain("对当前目标造成伤害");
        assertThat(kinds.targets(gameId)).containsExactly(row(4).getId());
        assertThat(kinds.countTargets(gameId)).isEqualTo(1);

        fake.textKindCalls.clear();
        mvc.perform(post("/api/games/{g}/rules/judge-kinds", gameId)).andExpect(status().isOk());
        assertThat(fake.textKindCalls).flatMap(b -> b).extracting(LlmClient.KindInput::zh).containsExactly("伤害减免提升至@10%@");
    }
}
