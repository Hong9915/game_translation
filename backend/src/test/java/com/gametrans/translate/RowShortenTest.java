package com.gametrans.translate;

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
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 검수 그리드 우클릭 "짧게 줄이기": GPT 후보만 돌려주고 저장하지 않는다. 저장은 사람이 후보를 고를 때 일반 저장으로 한다. */
class RowShortenTest extends IntegrationTest {
    static final String SRC = "正义裁决";   // 기존 한국어 "정의의 심판"(5자)

    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired SegmentRepository segments;
    @Autowired RevisionService revisions;
    @Autowired LlmClient llm;
    FakeLlmClient fakeLlm;

    Long pid;
    Long segmentId;

    @BeforeEach
    void setUp() throws Exception {
        fakeLlm = (FakeLlmClient) llm;
        fakeLlm.shortenCalls.clear();
        fakeLlm.shortenOptions = List.of();
        pid = TestProjects.uploadExistingKo(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE)).andExpect(status().isOk());
        segmentId = segments.findByProjectIdAndSheetNameAndSkippedFalseOrderByRowNoAscPairIndexAsc(pid, "Skill").stream()
                .filter(s -> SRC.equals(s.getSrcText())).findFirst().orElseThrow().getId();
    }

    private Long gameId() { return projects.findById(pid).orElseThrow().getGame().getId(); }

    @Test
    void returnsFilteredCandidatesWithoutSaving() throws Exception {
        // 같은 글·중복·빈 값은 버리고, 남은 후보에 서버가 센 글자 수를 붙인다
        // "정의의심판"은 띄어쓰기만 뺀 것이라 글자 수가 그대로다
        fakeLlm.shortenOptions = List.of("정의 심판", "정의의 심판", "정의의심판", "정의 심판", " ", "심판");
        Long before = revisions.current(segmentId).map(TranslationRevision::getId).orElse(null);

        mvc.perform(post("/api/segments/{sid}/shorten", segmentId).contentType(MediaType.APPLICATION_JSON).content("{\"maxChars\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ko").value("정의의 심판"))
                .andExpect(jsonPath("$.len").value(5))
                .andExpect(jsonPath("$.target").value(3))
                .andExpect(jsonPath("$.candidates.length()").value(2))
                .andExpect(jsonPath("$.candidates[0].ko").value("정의 심판"))
                .andExpect(jsonPath("$.candidates[0].len").value(4))
                .andExpect(jsonPath("$.candidates[1].ko").value("심판"))
                .andExpect(jsonPath("$.candidates[1].len").value(2));

        assertThat(revisions.current(segmentId).map(TranslationRevision::getId).orElse(null)).isEqualTo(before);
        LlmClient.ShortenOptionsRequest call = fakeLlm.shortenCalls.get(0);
        assertThat(call.cn()).isEqualTo(SRC);
        assertThat(call.en()).isEqualTo("Rule of Law");
        assertThat(call.ko()).isEqualTo("정의의 심판");
        assertThat(call.maxChars()).isEqualTo(3);
    }

    @Test
    void shortensTheLatestFixWhenThereIsOne() throws Exception {
        mvc.perform(put("/api/segments/{sid}/translation", segmentId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"정의로운 심판의 일격\"}")).andExpect(status().isOk());
        fakeLlm.shortenOptions = List.of("심판의 일격");

        mvc.perform(post("/api/segments/{sid}/shorten", segmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ko").value("정의로운 심판의 일격"));
        assertThat(fakeLlm.shortenCalls.get(0).ko()).isEqualTo("정의로운 심판의 일격");
    }

    @Test
    void defaultTargetIsSourceLengthTimesRatio() throws Exception {
        fakeLlm.shortenOptions = List.of("심판");

        mvc.perform(post("/api/segments/{sid}/shorten", segmentId).contentType(MediaType.APPLICATION_JSON).content("{\"ratio\":0.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target").value(2));   // 원문 4자 × 0.5
        mvc.perform(post("/api/segments/{sid}/shorten", segmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target").value(4));   // 기본 비율 1.0
    }

    @Test
    void theGlossaryAppliesToThePrompt() throws Exception {
        mvc.perform(post("/api/games/{id}/glossary", gameId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"SRC\",\"matchMode\":\"PARTIAL\",\"find\":\"裁决\",\"ko\":\"판결\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/games/{id}/glossary", gameId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"SRC\",\"matchMode\":\"PARTIAL\",\"find\":\"红包\",\"ko\":\"행운 봉투\"}")).andExpect(status().isOk());
        fakeLlm.shortenOptions = List.of("정의 판결");

        mvc.perform(post("/api/segments/{sid}/shorten", segmentId)).andExpect(status().isOk());

        assertThat(fakeLlm.shortenCalls.get(0).glossary()).containsEntry("裁决", "판결").doesNotContainKey("红包");
    }

    @Test
    void candidatesThatBreakPlaceholdersAreDropped() throws Exception {
        mvc.perform(put("/api/segments/{sid}/translation", segmentId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"{0}에게 정의의 심판을 내립니다\"}")).andExpect(status().isOk());
        fakeLlm.shortenOptions = List.of("정의의 심판", "{0}에게 심판");

        mvc.perform(post("/api/segments/{sid}/shorten", segmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.candidates[0].ko").value("{0}에게 심판"));
    }

    @Test
    void noUsableCandidateIsAConflict() throws Exception {
        fakeLlm.shortenOptions = List.of("정의의 심판");

        mvc.perform(post("/api/segments/{sid}/shorten", segmentId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("후보")));
    }

    @Test
    void rowsWithoutKoreanAreRejected() throws Exception {
        Segment empty = segments.findByProjectIdAndSheetNameAndSkippedFalseOrderByRowNoAscPairIndexAsc(pid, "Skill").stream()
                .filter(s -> "技能伤害提升至@420%@".equals(s.getSrcText())).findFirst().orElseThrow();
        mvc.perform(post("/api/segments/{sid}/shorten", empty.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("한국어")));


        assertThat(fakeLlm.shortenCalls).isEmpty();
    }
}
