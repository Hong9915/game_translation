package com.gametrans.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.glossary.GlossaryEntry.Kind;
import com.gametrans.glossary.GlossaryEntry.MatchMode;
import com.gametrans.llm.LlmClient;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.ProjectRepository;
import com.gametrans.review.TermMissService;
import java.time.Instant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 용어 불일치(term_miss)는 구 용어 표가 아니라 게임의 유효 단어장(SRC 항목 중 ko가 있는 것)으로 계산한다. */
class TermMissFromGlossaryTest extends IntegrationTest {
    static final String SRC = "正义裁决";

    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired SegmentRepository segments;
    @Autowired GlossaryEntryRepository glossaryRepo;
    @Autowired TermMissService termMiss;
    @Autowired LlmClient llm;

    Long pid;

    @BeforeEach
    void resetFake() {
        FakeLlmClient fake = (FakeLlmClient) llm;
        fake.fixTermsCalls.clear();
        fake.fixTermsNoop = false;
    }

    /** 기존 한국어가 있는 Skill 시트 하나짜리 프로젝트를 새 게임에 만들고 그 게임 id를 돌려준다. pid도 채운다. */
    private Long gameOfNewProject() throws Exception {
        pid = TestProjects.uploadExistingKo(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));
        return projects.findById(pid).orElseThrow().getGame().getId();
    }

    /** 원문 正义裁决, Fix "정의의 심판"인 행. */
    private Segment judgementRow() {
        return segments.findByProjectIdAndSheetNameAndSkippedFalseOrderByRowNoAscPairIndexAsc(pid, "Skill").stream()
                .filter(s -> SRC.equals(s.getSrcText())).findFirst().orElseThrow();
    }

    private String termMissOf(Long segmentId) {
        return segments.findById(segmentId).orElseThrow().getTermMiss();
    }

    private void addSrcPartial(Long g, String find, String ko) throws Exception {
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"SRC\",\"matchMode\":\"PARTIAL\",\"find\":\"" + find + "\",\"ko\":\"" + ko + "\"}"))
                .andExpect(status().isOk());
    }

    private void recompute(String sheet) throws Exception {
        mvc.perform(post("/api/review/{id}/term-miss/recompute", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sheet\":\"" + sheet + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void aSrcEntryWithoutItsKoInTheFixIsFlagged() throws Exception {
        Long g = gameOfNewProject();
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"SRC\",\"matchMode\":\"PARTIAL\",\"find\":\"裁决\",\"ko\":\"판결\"}"));

        mvc.perform(post("/api/review/{id}/term-miss/recompute", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sheet\":\"Skill\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        // 원문에 裁决이 있는데 Fix("정의의 심판")에 "판결"이 없다
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("filter", "TERM_MISS"))
                .andExpect(jsonPath("$.total").value(1));
        assertThat(termMissOf(judgementRow().getId())).isEqualTo("裁决→판결");

        // 이미 맞춰진 값은 다시 쓰지 않는다 — 두 번째 재계산은 바뀐 행이 없다
        mvc.perform(post("/api/review/{id}/term-miss/recompute", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sheet\":\"Skill\"}"))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void memoOnlyEntriesAreNotCounted() throws Exception {
        Long g = gameOfNewProject();
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"SRC\",\"matchMode\":\"PARTIAL\",\"find\":\"裁决\",\"note\":\"헷갈림\"}"));
        mvc.perform(post("/api/review/{id}/term-miss/recompute", pid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sheet\":\"Skill\"}"));
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill").param("filter", "TERM_MISS"))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void recomputeNeedsASheet() throws Exception {
        gameOfNewProject();
        mvc.perform(post("/api/review/{id}/term-miss/recompute", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sheet\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("시트")));
        mvc.perform(post("/api/review/{id}/term-miss/recompute", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void savingACellChecksTheGlossary() throws Exception {
        Long g = gameOfNewProject();
        addSrcPartial(g, "裁决", "판결");
        Long id = judgementRow().getId();

        mvc.perform(put("/api/segments/{id}/translation", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"정의의 심판관\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.termMiss[0]").value("裁决→판결"));
        assertThat(termMissOf(id)).isEqualTo("裁决→판결");

        mvc.perform(put("/api/segments/{id}/translation", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"정의의 판결\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.termMiss.length()").value(0));
        assertThat(termMissOf(id)).isNull();
    }

    @Test
    void applyingAnEntryRechecksTheTouchedRowsAgainstTheGlossary() throws Exception {
        Long g = gameOfNewProject();
        addSrcPartial(g, "裁决", "판결");
        Long id = judgementRow().getId();
        assertThat(termMissOf(id)).isNull();   // SRC+PARTIAL은 자동 적용이 없어 아직 아무 행도 안 건드렸다

        // KO 항목 적용이 "정의의 심판" → "정의의 재판"으로 바꾸면서 그 행을 다시 검사한다
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"KO\",\"matchMode\":\"PARTIAL\",\"find\":\"심판\",\"ko\":\"재판\"}"))
                .andExpect(jsonPath("$.applied").value(1));
        assertThat(termMissOf(id)).isEqualTo("裁决→판결");
    }

    @Test
    void aGameEntryBeatsTheSharedEntryWithTheSameFind() throws Exception {
        Long g = gameOfNewProject();
        // 공유 단어집은 테스트 사이에 남으므로 이 테스트에서만 쓰는 find로 만든다
        String find = "共享词" + System.nanoTime();
        GlossaryEntry shared = new GlossaryEntry();
        shared.setGameId(null); shared.setKind(Kind.SRC); shared.setMatchMode(MatchMode.PARTIAL);
        shared.setFind(find); shared.setKo("공유 번역"); shared.setUpdatedAt(Instant.now());
        glossaryRepo.save(shared);
        assertThat(termMiss.glossaryFor(pid)).containsEntry(find, "공유 번역");

        addSrcPartial(g, find, "게임 번역");
        assertThat(termMiss.glossaryFor(pid)).containsEntry(find, "게임 번역");
    }

    @Test
    void fixTermsKeepsAMarkThatTheGlossaryStillWants() throws Exception {
        Long g = gameOfNewProject();
        addSrcPartial(g, "裁决", "판결");
        recompute("Skill");
        Long id = judgementRow().getId();
        assertThat(termMissOf(id)).isEqualTo("裁决→판결");

        // GPT가 아무것도 못 고친 채 돌려주면 표시는 그대로 남아야 한다 — 구 용어 표로 다시 보면 "빠진 게 없다"며 풀어 버린다
        ((FakeLlmClient) llm).fixTermsNoop = true;
        mvc.perform(post("/api/projects/{id}/fix-terms", pid).contentType(MediaType.APPLICATION_JSON).content("{\"sheet\":\"Skill\"}"))
                .andExpect(jsonPath("$.status").value("DONE"));
        assertThat(((FakeLlmClient) llm).fixTermsCalls).hasSize(1);
        assertThat(termMissOf(id)).isEqualTo("裁决→판결");
    }
}
