package com.gametrans.translate;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 기존 번역 교정 모드: 기존 한국어를 살리고, GPT가 틀렸다고 본 행만 새 판으로 고친다. */
class ProofreadFlowTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;
    FakeLlmClient fake;

    @BeforeEach
    void setUp() {
        fake = (FakeLlmClient) llm;
        fake.translateCalls.clear();
        fake.translations.clear();
        fake.proofreadCalls.clear();
        fake.proofFixes.clear();
        fake.dropOnce.clear();
        fake.breakPlaceholdersOnce.clear();
    }

    private long normalized() throws Exception {
        long pid = TestProjects.uploadProofread(mvc);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.PROOFREAD_NORMALIZE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configs[0].config.proofreadExisting").value(true))
                .andExpect(jsonPath("$.configs[0].config.retranslateExisting").value(false))
                .andExpect(jsonPath("$.stats.kept").value(3));
        return pid;
    }

    private void translate(long pid) throws Exception {
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"))
                .andExpect(status().isOk());
    }

    @Autowired com.gametrans.glossary.GlossaryEntryRepository glossaryRepo;
    @Autowired com.gametrans.project.ProjectRepository projects;

    /** 교정이 유지로 판정한 행의 용어 누락도 게임의 유효 단어장으로 매기고, 교정 프롬프트에도 그 단어장이 들어간다. */
    @Test
    void keptRowsAreCheckedAgainstTheGlossary() throws Exception {
        long pid = normalized();
        com.gametrans.glossary.GlossaryEntry g = new com.gametrans.glossary.GlossaryEntry();
        g.setGameId(projects.findById(pid).orElseThrow().getGame().getId());
        g.setKind(com.gametrans.glossary.GlossaryEntry.Kind.SRC); g.setMatchMode(com.gametrans.glossary.GlossaryEntry.MatchMode.PARTIAL);
        g.setFind("属性"); g.setKo("속성"); g.setUpdatedAt(java.time.Instant.now());
        glossaryRepo.save(g);

        translate(pid);   // 가짜 교정은 전부 KEEP — 属性 행은 "재산"이 그대로 남는다

        assertThat(fake.proofreadCalls).isNotEmpty();
        assertThat(fake.proofreadCalls.get(0).glossary()).containsEntry("属性", "속성");
        mvc.perform(get("/api/projects/{id}/segments", pid))
                .andExpect(jsonPath("$.rows[?(@.srcText=='属性')].termMiss[0]").value("属性→속성"));
    }

    @Test
    void pendingProofreadRowsCountAsNotDone() throws Exception {
        long pid = normalized();
        mvc.perform(get("/api/projects/{id}/translate", pid))
                .andExpect(jsonPath("$.targetRows").value(4))
                .andExpect(jsonPath("$.doneRows").value(0))
                .andExpect(jsonPath("$.proofPendingRows").value(3));
    }

    @Test
    void pendingProofreadRowsCanBeListedOnTheirOwnInReview() throws Exception {
        long pid = normalized();
        mvc.perform(get("/api/projects/{id}/segments", pid).param("proofPending", "true"))
                .andExpect(jsonPath("$.stats.proofPending").value(3))
                .andExpect(jsonPath("$.rows.length()").value(3))
                .andExpect(jsonPath("$.rows[*].indexLabel").value(org.hamcrest.Matchers.contains("Code!2", "Code!3", "Code!4")))
                .andExpect(jsonPath("$.rows[0].proofPending").value(true));
        translate(pid);
        mvc.perform(get("/api/projects/{id}/segments", pid).param("proofPending", "true"))
                .andExpect(jsonPath("$.stats.proofPending").value(0))
                .andExpect(jsonPath("$.rows.length()").value(0));
    }

    @Test
    void keepsGoodRowsAndFixesOnlyTheWrongOne() throws Exception {
        long pid = normalized();
        fake.proofFixes.put("재산", "속성");
        translate(pid);

        // 빈칸 행만 번역으로, 기존 한국어가 있는 세 행은 교정으로 간다
        assertThat(fake.translateCalls).hasSize(1);
        assertThat(fake.translateCalls.get(0).rows()).extracting(r -> r.index()).containsExactly("Code!5");
        assertThat(fake.proofreadCalls).hasSize(1);
        assertThat(fake.proofreadCalls.get(0).rows()).extracting(r -> r.index()).containsExactly("Code!2", "Code!3", "Code!4");
        assertThat(fake.proofreadCalls.get(0).rows().get(1).ko()).isEqualTo("재산");

        mvc.perform(get("/api/projects/{id}/segments", pid))
                .andExpect(jsonPath("$.rows[0].currentText").value("게임을 종료하시겠습니까?"))
                .andExpect(jsonPath("$.rows[0].history.length()").value(1))
                .andExpect(jsonPath("$.rows[0].proofNote").doesNotExist())
                .andExpect(jsonPath("$.rows[1].currentText").value("속성"))
                .andExpect(jsonPath("$.rows[1].history[0].author").value("EXISTING"))
                .andExpect(jsonPath("$.rows[1].history[1].author").value("LLM_PROOFREAD"))
                .andExpect(jsonPath("$.rows[1].proofNote").value("MISTRANSLATION: 테스트"))
                .andExpect(jsonPath("$.rows[2].history.length()").value(1))
                .andExpect(jsonPath("$.stats.proofFixed").value(1));

        mvc.perform(get("/api/projects/{id}/translate", pid))
                .andExpect(jsonPath("$.doneRows").value(4))
                .andExpect(jsonPath("$.proofPendingRows").value(0));

        // 다시 돌려도 이미 판정한 행은 또 묻지 않는다
        translate(pid);
        assertThat(fake.proofreadCalls).hasSize(1);
    }

    @Test
    void aFixThatBreaksPlaceholdersIsRejectedAndTheExistingTextIsKept() throws Exception {
        long pid = normalized();
        fake.proofFixes.put("{0} 시간 후에 만료됩니다.", "시간 후 만료");
        translate(pid);
        mvc.perform(get("/api/projects/{id}/segments", pid))
                .andExpect(jsonPath("$.rows[2].currentText").value("{0} 시간 후에 만료됩니다."))
                .andExpect(jsonPath("$.rows[2].history.length()").value(1))
                .andExpect(jsonPath("$.rows[2].proofNote").doesNotExist());
        // 두 번 물어도 못 고친 행은 판정 대기로 남기지 않고 기존 문장을 유지로 확정한다 — 다시 돌려도 같은 결과라 다음 단계가 막히기만 한다
        assertThat(fake.proofreadCalls).hasSize(2);
        mvc.perform(get("/api/projects/{id}/translate", pid))
                .andExpect(jsonPath("$.doneRows").value(4))
                .andExpect(jsonPath("$.proofPendingRows").value(0));
        mvc.perform(get("/api/projects/{id}", pid)).andExpect(jsonPath("$.currentStep").value(5));
        translate(pid);
        assertThat(fake.proofreadCalls).hasSize(2);
    }

    @Test
    void rowsTheModelSkippedAreAskedAgain() throws Exception {
        long pid = normalized();
        fake.dropOnce.add("Code!3");
        translate(pid);
        assertThat(fake.proofreadCalls).hasSize(2);
        assertThat(fake.proofreadCalls.get(1).rows()).extracting(r -> r.index()).containsExactly("Code!3");
        mvc.perform(get("/api/projects/{id}/translate", pid)).andExpect(jsonPath("$.proofPendingRows").value(0));
    }
}
