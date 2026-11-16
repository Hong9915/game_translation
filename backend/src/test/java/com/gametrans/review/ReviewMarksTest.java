package com.gametrans.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 검수 체크(1차·2차·확인 필요)와 비고(설계 2026-09-28). 체크는 표시일 뿐 잠그지 않고, 같은 원문 전파는 2차 행을 건너뛴다. */
class ReviewMarksTest extends IntegrationTest {
    static final String SRC = "正义裁决";

    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired LlmClient llm;

    Long pid;

    private List<Segment> judgementRows() {
        return segments.findByProjectIdAndSheetNameAndSkippedFalseOrderByRowNoAscPairIndexAsc(pid, "Skill").stream()
                .filter(s -> SRC.equals(s.getSrcText())).toList();
    }

    private Long setUp(boolean twice) throws Exception {
        pid = twice ? TestProjects.uploadExistingKoSameSourceTwice(mvc, TestProjects.freshGame())
                : TestProjects.uploadExistingKo(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE)).andExpect(status().isOk());
        return judgementRows().get(0).getId();
    }

    private org.springframework.test.web.servlet.ResultActions marks(Long sid, String json) throws Exception {
        return mvc.perform(put("/api/segments/{sid}/marks", sid).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void review1AndReview2AreIndependent() throws Exception {
        Long sid = setUp(false);
        // 1차를 켜도 2차는 그대로
        marks(sid, "{\"review1\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.review1").value(true)).andExpect(jsonPath("$.review2").value(false))
                .andExpect(jsonPath("$.needsCheck").value(false));
        marks(sid, "{\"review2\":true}")
                .andExpect(jsonPath("$.review1").value(true)).andExpect(jsonPath("$.review2").value(true));
        // 1차를 꺼도 2차는 그대로
        marks(sid, "{\"review1\":false}")
                .andExpect(jsonPath("$.review1").value(false)).andExpect(jsonPath("$.review2").value(true));
    }

    @Test
    void review3IsIndependentAndCountedAndFiltered() throws Exception {
        Long sid = setUp(false);
        marks(sid, "{\"review3\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.review3").value(true))
                .andExpect(jsonPath("$.review1").value(false)).andExpect(jsonPath("$.review2").value(false));
        // A·B를 바꿔도 C는 그대로
        marks(sid, "{\"review1\":true,\"review2\":true}").andExpect(jsonPath("$.review3").value(true));
        mvc.perform(get("/api/review/{id}/rows/summary", pid).param("sheet", "Skill"))
                .andExpect(jsonPath("$.review3").value(1));
        String left = mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("filter", "NOT_REVIEW3"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Number> ids = com.jayway.jsonpath.JsonPath.read(left, "$.rows[*].id");
        assertThat(ids.stream().map(Number::longValue)).doesNotContain(sid);
        // 끄면 다시 안 함 쪽으로
        marks(sid, "{\"review3\":false}").andExpect(jsonPath("$.review3").value(false)).andExpect(jsonPath("$.review1").value(true));
    }

    @Test
    void needsCheckAndNoteAreIndependent() throws Exception {
        Long sid = setUp(false);
        marks(sid, "{\"needsCheck\":true,\"note\":\"  길이 확인  \"}")
                .andExpect(jsonPath("$.needsCheck").value(true)).andExpect(jsonPath("$.review1").value(false))
                .andExpect(jsonPath("$.note").value("길이 확인"));
        // 비고를 보내지 않으면 그대로
        marks(sid, "{\"needsCheck\":false}").andExpect(jsonPath("$.note").value("길이 확인"));
        // 빈 글이면 지운다
        marks(sid, "{\"note\":\"   \"}").andExpect(jsonPath("$.note").doesNotExist());
    }

    @Test
    void reviewedRowsStillTranslateAndShorten() throws Exception {
        Long sid = setUp(false);
        marks(sid, "{\"review1\":true}").andExpect(status().isOk());
        ((FakeLlmClient) llm).shortenOptions = List.of("심판");
        mvc.perform(post("/api/segments/{sid}/translate", sid)).andExpect(status().isOk());
        mvc.perform(put("/api/segments/{sid}/translation", sid).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"정의의 심판\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/segments/{sid}/shorten", sid)).andExpect(status().isOk());
    }

    @Test
    void confirmedIsIndependentOfOtherMarks() throws Exception {
        Long sid = setUp(false);
        marks(sid, "{\"confirmed\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed").value(true)).andExpect(jsonPath("$.review1").value(false))
                .andExpect(jsonPath("$.review2").value(false));
        marks(sid, "{\"review2\":true}").andExpect(jsonPath("$.confirmed").value(true));
        marks(sid, "{\"confirmed\":false}").andExpect(jsonPath("$.confirmed").value(false)).andExpect(jsonPath("$.review2").value(true));
    }

    @Test
    void sameSourceSaveSkipsConfirmedRows() throws Exception {
        setUp(true);
        List<Segment> two = judgementRows();
        Long edited = two.get(0).getId(), reviewed = two.get(1).getId();
        marks(reviewed, "{\"confirmed\":true}").andExpect(status().isOk());

        mvc.perform(put("/api/segments/{sid}/translation", edited).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"정의 판결\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/review/{pid}/rows", pid).param("sheet", "Skill"))
                .andExpect(jsonPath("$.rows[?(@.id == " + edited + ")].fix").value("정의 판결"))
                .andExpect(jsonPath("$.rows[?(@.id == " + reviewed + ")].fix").value("정의의 심판"));
    }

    @Test
    void review1AndReview2NoLongerBlockSameSourceSave() throws Exception {
        setUp(true);
        List<Segment> two = judgementRows();
        Long edited = two.get(0).getId(), reviewed = two.get(1).getId();
        marks(reviewed, "{\"review1\":true,\"review2\":true}").andExpect(status().isOk());

        mvc.perform(put("/api/segments/{sid}/translation", edited).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"정의 판결\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/review/{pid}/rows", pid).param("sheet", "Skill"))
                .andExpect(jsonPath("$.rows[?(@.id == " + reviewed + ")].fix").value("정의 판결"));
    }

    @Test
    void searchFindsNote() throws Exception {
        Long sid = setUp(false);
        marks(sid, "{\"note\":\"특이한 메모\"}").andExpect(status().isOk());
        mvc.perform(get("/api/review/{pid}/rows", pid).param("sheet", "Skill").param("q", "특이한"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].id").value(sid));
        assertThat(segments.findById(sid).orElseThrow().getNote()).isEqualTo("특이한 메모");
    }
}
