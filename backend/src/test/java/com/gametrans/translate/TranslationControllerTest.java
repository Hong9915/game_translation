package com.gametrans.translate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.llm.LlmClient;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class TranslationControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;
    @Autowired SegmentRepository segments;
    @Autowired RevisionService revisions;
    FakeLlmClient fake;

    @BeforeEach
    void setUp() {
        fake = (FakeLlmClient) llm;
        fake.translateCalls.clear();
        fake.translations.clear();
        fake.translations.put("使用后恢复{0}点生命值，冷却{1}秒。", "사용 시 HP {0} 회복. 재사용 대기 {1}초.");
        fake.dropOnce = new java.util.HashSet<>(Set.of("ItemText!3"));
        fake.breakPlaceholdersOnce = new java.util.HashSet<>(Set.of("ItemText!6"));
    }

    @Autowired com.gametrans.term.TermRepository termRepo;
    @Autowired com.gametrans.memory.MemoryEntryRepository memoryRepo;
    @Autowired com.gametrans.glossary.GlossaryEntryRepository glossaryRepo;
    @Autowired com.gametrans.project.ProjectRepository projects;

    /** 그 프로젝트가 속한 게임의 단어장에 SRC·PARTIAL 항목을 하나 넣는다. */
    private void glossaryEntry(long pid, String find, String ko) {
        com.gametrans.glossary.GlossaryEntry g = new com.gametrans.glossary.GlossaryEntry();
        g.setGameId(projects.findById(pid).orElseThrow().getGame().getId());
        g.setKind(com.gametrans.glossary.GlossaryEntry.Kind.SRC); g.setMatchMode(com.gametrans.glossary.GlossaryEntry.MatchMode.PARTIAL);
        g.setFind(find); g.setKo(ko); g.setUpdatedAt(java.time.Instant.now());
        glossaryRepo.save(g);
    }

    /**
     * 번역 프롬프트에는 구 용어와 단어장이 함께 들어가고(같은 원문이면 단어장), 용어 누락 표시·재시도는 단어장만 본다.
     * 구 용어(长剑)가 빠진 번역은 다시 묻지도, 표시하지도 않는다.
     */
    @Test
    void thePromptKeepsOldTermsButTermMissFollowsTheGlossary() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        for (String[] kv : new String[][] {{"长剑", "장검"}, {"火焰", "불"}}) {
            com.gametrans.term.Term t = new com.gametrans.term.Term();
            t.setProjectId(pid); t.setSrcText(kv[0]); t.setType(com.gametrans.term.Term.Type.OTHER);
            t.setConfirmedKo(kv[1]); t.setConfirmed(true);
            termRepo.save(t);
        }
        glossaryEntry(pid, "火焰", "화염");
        fake.dropOnce.clear(); fake.breakPlaceholdersOnce.clear();
        fake.translations.put("一把附有火焰之力的长剑，攻击时有概率造成灼烧。", "화염의 힘이 깃든 롱소드.");   // 장검 없음, 화염 있음

        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"basis\":\"CN_ONLY\",\"batchSize\":10}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));

        List<LlmClient.TranslateBatchRequest> calls = fake.translateCalls.stream()
                .filter(c -> c.rows().stream().anyMatch(r -> r.index().equals("ItemText!3"))).toList();
        assertThat(calls).hasSize(1);   // 단어장 용어가 들어 있으니 다시 묻지 않는다
        assertThat(calls.get(0).glossary()).containsEntry("长剑", "장검").containsEntry("火焰", "화염");
        mvc.perform(get("/api/projects/{id}/segments", pid))
                .andExpect(jsonPath("$.rows[?(@.indexLabel=='ItemText!3')].termMiss[*]").isEmpty())
                .andExpect(jsonPath("$.stats.termMiss").value(0));
    }

    /** 확정 용어는 그 용어가 들어 있는 묶음의 프롬프트에만 들어간다. */
    @Test
    void glossaryIsFilteredToTermsPresentInEachBatch() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        com.gametrans.term.Term t = new com.gametrans.term.Term();
        t.setProjectId(pid); t.setSrcText("火焰"); t.setType(com.gametrans.term.Term.Type.OTHER);
        t.setConfirmedKo("화염"); t.setConfirmed(true);
        termRepo.save(t);
        fake.dropOnce.clear(); fake.breakPlaceholdersOnce.clear();
        fake.translations.put("一把附有火焰之力的长剑，攻击时有概率造成灼烧。", "화염의 힘이 깃든 장검.");  // 용어 지킴 → 재시도 없음

        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"basis\":\"CN_ONLY\",\"batchSize\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));

        // 묶음1(2·3행): 3행 원문에 火焰 포함 → 용어집 포함. 묶음2(5·6행)·묶음3(7행): 없음 → 빈 용어집
        assertThat(fake.translateCalls).hasSize(3);
        assertThat(fake.translateCalls.get(0).glossary()).containsExactly(java.util.Map.entry("火焰", "화염"));
        assertThat(fake.translateCalls.get(1).glossary()).isEmpty();
        assertThat(fake.translateCalls.get(2).glossary()).isEmpty();
    }

    @Test
    void translatesAllRowsInBatchesWithRetries() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);

        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"basis\":\"EN_FIRST\",\"instructions\":\"해요체\",\"batchSize\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.totalBatches").value(3))
                .andExpect(jsonPath("$.doneBatches").value(3))
                .andExpect(jsonPath("$.logs[0]").value("묶음 3개를 최대 1개씩 동시에 GPT에 보냅니다"))
                .andExpect(jsonPath("$.logs[1]").value("묶음 1/3 (2행) 번역 중…"));

        List<Segment> rows = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid);
        assertThat(rows).hasSize(5);
        for (Segment s : rows) assertThat(revisions.current(s.getId())).as(s.getIndexLabel()).isPresent();
        assertThat(revisions.current(rows.get(3).getId()).get().getText()).isEqualTo("사용 시 HP {0} 회복. 재사용 대기 {1}초.");
        assertThat(fake.translateCalls).hasSize(5); // 3 묶음 + 누락 재시도 1 + 자리표시자 재시도 1
        assertThat(fake.translateCalls.get(0).basis()).isEqualTo("EN_FIRST");
        assertThat(fake.translateCalls.get(0).instructions()).isEqualTo("해요체");
        assertThat(segments.findUntranslated(pid)).isEmpty();
        assertThat(segments.countUntranslated(pid)).isZero();

        mvc.perform(get("/api/projects/{id}", pid)).andExpect(jsonPath("$.currentStep").value(5));
        mvc.perform(get("/api/projects/{id}/translate", pid))
                .andExpect(jsonPath("$.targetRows").value(5))
                .andExpect(jsonPath("$.doneRows").value(5))
                .andExpect(jsonPath("$.batchSize").value(2))
                .andExpect(jsonPath("$.job.status").value("DONE"));
    }

    @Test
    void secondRunOnlyTranslatesRemainingRows() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"basis\":\"CN_ONLY\",\"batchSize\":30}"));
        fake.translateCalls.clear();
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"basis\":\"CN_ONLY\",\"batchSize\":30}"))
                .andExpect(jsonPath("$.totalBatches").value(0));
        assertThat(fake.translateCalls).isEmpty();
    }

    /** 단어장 용어의 한국어가 결과에 없으면 한 번 더 시도하고, 그래도 없으면 저장하되 용어 누락으로 표시한다. 사람이 고치면 표시가 사라진다. */
    @Test
    void glossaryMissIsRetriedThenFlaggedUntilAHumanFixesIt() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        glossaryEntry(pid, "火焰", "화염");
        fake.dropOnce.clear(); fake.breakPlaceholdersOnce.clear();
        fake.translations.put("一把附有火焰之力的长剑，攻击时有概率造成灼烧。", "불꽃의 힘이 깃든 장검. 공격 시 화상을 입힐 수 있다.");  // 화염 없음

        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"basis\":\"CN_ONLY\",\"batchSize\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.logs[*]").value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("용어 누락 1행"))));

        // 3행은 처음 호출과 재시도, 두 번 GPT에 갔다
        long calls = fake.translateCalls.stream().filter(c -> c.rows().stream().anyMatch(r -> r.index().equals("ItemText!3"))).count();
        assertThat(calls).isEqualTo(2);

        String body = mvc.perform(get("/api/projects/{id}/segments", pid).param("flagged", "true").param("ratio", "10"))
                .andExpect(jsonPath("$.stats.termMiss").value(1))
                .andExpect(jsonPath("$.stats.flagged").value(0))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].indexLabel").value("ItemText!3"))
                .andExpect(jsonPath("$.rows[0].currentText").value("불꽃의 힘이 깃든 장검. 공격 시 화상을 입힐 수 있다."))
                .andExpect(jsonPath("$.rows[0].termMiss[0]").value("火焰→화염"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long segId = com.jayway.jsonpath.JsonPath.parse(body).read("$.rows[0].id", Long.class);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/segments/{id}/translation", segId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"화염의 힘이 깃든 장검. 공격 시 화상을 입힐 수 있다.\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.row.termMiss.length()").value(0));
        mvc.perform(get("/api/projects/{id}/segments", pid).param("flagged", "true").param("ratio", "10"))
                .andExpect(jsonPath("$.stats.termMiss").value(0))
                .andExpect(jsonPath("$.rows.length()").value(0));
    }

    /** 번역 메모리 문장이 지금 단어장과 어긋나면 그대로 채우지 않고 GPT로 보낸다. */
    @Test
    void memoryTextThatMissesAGlossaryTermIsNotReused() throws Exception {
        String game = "메모리-용어-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        long pid = TestProjects.upload(mvc, game);
        TestProjects.normalize(mvc, pid);
        glossaryEntry(pid, "火焰", "화염");
        com.gametrans.memory.MemoryEntry m = new com.gametrans.memory.MemoryEntry();
        m.setGameName(game); m.setSrcText("一把附有火焰之力的长剑，攻击时有概率造成灼烧。"); m.setKo("불꽃 장검"); m.setSource(com.gametrans.memory.MemoryEntry.Source.EXPORT);
        memoryRepo.save(m);
        fake.dropOnce.clear(); fake.breakPlaceholdersOnce.clear();
        fake.translations.put("一把附有火焰之力的长剑，攻击时有概率造成灼烧。", "화염의 힘이 깃든 장검.");

        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"basis\":\"CN_ONLY\",\"batchSize\":10}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        mvc.perform(get("/api/projects/{id}/segments", pid))
                .andExpect(jsonPath("$.rows[?(@.indexLabel=='ItemText!3')].currentText").value("화염의 힘이 깃든 장검."))
                .andExpect(jsonPath("$.rows[?(@.indexLabel=='ItemText!3')].history[0].author").value("LLM"))
                .andExpect(jsonPath("$.stats.termMiss").value(0));
    }
}
