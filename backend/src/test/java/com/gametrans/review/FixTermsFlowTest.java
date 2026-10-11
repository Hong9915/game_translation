package com.gametrans.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.glossary.GlossaryEntry;
import com.gametrans.glossary.GlossaryEntryRepository;
import com.gametrans.llm.LlmClient;
import com.gametrans.project.ProjectRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 용어 고치기: 확정 용어가 빠진 행만 GPT에 다시 보내 그 용어를 넣은 수정본을 쌓는다. */
class FixTermsFlowTest extends IntegrationTest {
    static final String SWORD = "一把附有火焰之力的长剑，攻击时有概率造成灼烧。";
    static final String SWORD_KO = "불꽃의 힘이 깃든 장검. 공격 시 화상을 입힐 수 있다.";

    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;
    @Autowired GlossaryEntryRepository glossaryRepo;
    @Autowired ProjectRepository projects;
    FakeLlmClient fake;

    @BeforeEach
    void setUp() {
        fake = (FakeLlmClient) llm;
        fake.dropOnce.clear(); fake.breakPlaceholdersOnce.clear();
        fake.fixTermsCalls.clear(); fake.fixTermsNoop = false;
    }

    /**
     * 火焰의 단어장 항목에 메모가 붙어 있고, 번역은 그 용어를 빠뜨린 상태. 번역 작업도 용어 고치기도 게임의 유효 단어장으로
     * 표시를 매긴다 — 구 용어 표에는 아무것도 두지 않는다.
     */
    private long translatedWithMiss() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        GlossaryEntry g = new GlossaryEntry();
        g.setGameId(projects.findById(pid).orElseThrow().getGame().getId());
        g.setKind(GlossaryEntry.Kind.SRC); g.setMatchMode(GlossaryEntry.MatchMode.PARTIAL);
        g.setFind("火焰"); g.setKo("화염"); g.setNote("의역: 불꽃 속성"); g.setUpdatedAt(java.time.Instant.now());
        glossaryRepo.save(g);
        fake.translations.put(SWORD, SWORD_KO);
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"))
                .andExpect(jsonPath("$.status").value("DONE"));
        mvc.perform(get("/api/projects/{id}/segments", pid).param("ratio", "10"))
                .andExpect(jsonPath("$.stats.termMiss").value(1))
                .andExpect(jsonPath("$.rows[?(@.srcText == '" + SWORD + "')].termMiss[0]").value("火焰→화염"));
        return pid;
    }

    @Test
    void sendsOnlyTermMissRowsAndStoresTheFixAsANewRevision() throws Exception {
        long pid = translatedWithMiss();
        mvc.perform(post("/api/projects/{id}/fix-terms", pid).contentType(MediaType.APPLICATION_JSON).content("{\"ratio\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("FIX_TERMS"))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.logs[*]").value(Matchers.hasItem(Matchers.containsString("완료: 1행"))));

        assertThat(fake.fixTermsCalls).hasSize(1);
        assertThat(fake.fixTermsCalls.get(0)).singleElement().satisfies(r -> {
            assertThat(r.ko()).isEqualTo(SWORD_KO);
            // 단어장 항목의 메모가 예전 확정 번역처럼 "번역어 (메모)" 모양으로 같이 간다 — 프롬프트가 메모를 떼어 참고로 보여 준다
            assertThat(r.missing()).containsExactly(java.util.Map.entry("火焰", "화염 (의역: 불꽃 속성)"));
        });
        mvc.perform(get("/api/projects/{id}/segments", pid).param("ratio", "10"))
                .andExpect(jsonPath("$.stats.termMiss").value(0))
                .andExpect(jsonPath("$.rows[?(@.srcText == '" + SWORD + "')].currentText").value("화염 " + SWORD_KO))
                .andExpect(jsonPath("$.rows[?(@.srcText == '" + SWORD + "')].history[-1].author").value("LLM_PROOFREAD"))
                .andExpect(jsonPath("$.rows[?(@.srcText == '" + SWORD + "')].proofNote").value("GLOSSARY: 용어 반영 火焰"));
    }

    @Test
    void anAnswerThatStillMissesTheTermIsDropped() throws Exception {
        long pid = translatedWithMiss();
        fake.fixTermsNoop = true;
        mvc.perform(post("/api/projects/{id}/fix-terms", pid).contentType(MediaType.APPLICATION_JSON).content("{\"ratio\":10}"))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.logs[*]").value(Matchers.hasItem(Matchers.containsString("못 고침 1행"))));
        mvc.perform(get("/api/projects/{id}/segments", pid).param("ratio", "10"))
                .andExpect(jsonPath("$.stats.termMiss").value(1))
                .andExpect(jsonPath("$.rows[?(@.srcText == '" + SWORD + "')].history.length()").value(1));
    }

    @Test
    void aSelectionOutsideTheTermMissRowsSendsNothing() throws Exception {
        long pid = translatedWithMiss();
        mvc.perform(post("/api/projects/{id}/fix-terms", pid).contentType(MediaType.APPLICATION_JSON).content("{\"ratio\":10,\"segmentIds\":[-1]}"))
                .andExpect(jsonPath("$.status").value("DONE"));
        assertThat(fake.fixTermsCalls).isEmpty();
    }
}
