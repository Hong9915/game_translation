package com.gametrans.term;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.job.JobService;
import com.gametrans.job.TranslationJob;
import com.gametrans.llm.LlmClient;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 용어집 정리: 숫자만 다른 변형(11星英雄·12星英雄)을 기본 단어(星英雄) 하나로 접는다.
 * 기본 단어가 없으면(钻石秘境) GPT 후보로 만들어 바로 확정하고, 변형은 게임 용어집과 파일 용어에서 지우되 그 행의 번역은 그대로 둔다.
 */
class CollapseTermsFlowTest extends IntegrationTest {
    static final String DESC = "击败12星英雄可进入1级钻石秘境";

    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;
    @Autowired JobService jobs;
    @Autowired com.gametrans.glossary.GlossaryEntryRepository glossaryRepo;
    FakeLlmClient fake;
    String game;
    long gameId, pid;

    @BeforeEach
    void setUp() throws Exception {
        fake = (FakeLlmClient) llm;
        fake.termsToReturn = List.of();
        fake.candidatesToReturn.clear();
        fake.suggestFixedParts.clear();
        fake.translations.put(DESC, "12성 영웅을 처치하면 1단계 다이아 비밀 지역에 들어갈 수 있다");
        game = "용어집 정리 " + System.nanoTime();
        pid = TestProjects.uploadNumeric(mvc, game);
        gameId = JsonPath.parse(body("/api/projects/{id}", pid)).read("$.gameId", Long.class);
        TestProjects.normalize(mvc, pid);
        // 이미 쌓인 상태를 흉내 낸다: 변형이 각각 확정된 용어집. 추출을 거치지 않고 직접 넣는다(추출은 이제 변형을 접는다).
        create("星英雄", "CHARACTER", "성 영웅");
        create("11星英雄", "CHARACTER", "11성 영웅");
        create("12星英雄", "CHARACTER", "12성급 캐릭터");
        create("钻石", "ITEM", "다이아");
        create("1级钻石秘境", "PLACE", "1단계 다이아 비밀 지역");
        create("2级钻石秘境", "PLACE", "2레벨 다이아 성역");
        create("7级黑暗城堡", "PLACE", "7단계 어둠의 성");   // 기본 단어(黑暗城堡)가 용어집에 없고 변형도 하나뿐
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"))
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    private String body(String url, Object... vars) throws Exception {
        return mvc.perform(get(url, vars)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void create(String src, String type, String ko) throws Exception {
        mvc.perform(post("/api/projects/{id}/terms", pid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"srcText\":\"" + src + "\",\"type\":\"" + type + "\",\"confirmedKo\":\"" + ko + "\"}")).andExpect(status().isCreated());
    }

    private List<String> gameSrcs() throws Exception {
        return JsonPath.parse(body("/api/games/{id}/terms", gameId)).read("$[*].srcText");
    }

    @Test
    void collapsesVariantsCreatesMissingStemsAndKeepsRowTranslations() throws Exception {
        fake.candidatesToReturn.put("钻石秘境", List.of(new LlmClient.Candidate("다이아 비경", "MEANING")));
        // 용어 불일치는 게임의 유효 단어장으로만 매긴다. 단어장에는 星英雄만 두고, 정리가 새로 만드는 钻石秘境은 구 용어 표에만 생긴다.
        com.gametrans.glossary.GlossaryEntry g = new com.gametrans.glossary.GlossaryEntry();
        g.setGameId(gameId);
        g.setKind(com.gametrans.glossary.GlossaryEntry.Kind.SRC); g.setMatchMode(com.gametrans.glossary.GlossaryEntry.MatchMode.PARTIAL);
        g.setFind("星英雄"); g.setKo("성 영웅"); g.setUpdatedAt(java.time.Instant.now());
        glossaryRepo.save(g);

        mvc.perform(post("/api/games/{id}/terms/collapse", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("COLLAPSE_TERMS"))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.logs[*]").value(Matchers.hasItem(Matchers.containsString("숫자 변형 4개 → 기본 단어 2개 (이미 있음 1 · 새로 만듦 1)"))))
                .andExpect(jsonPath("$.logs[*]").value(Matchers.hasItem(Matchers.containsString("새 용어: 钻石秘境 = 다이아 비경"))))
                .andExpect(jsonPath("$.logs[*]").value(Matchers.hasItem(Matchers.containsString("건너뜀: 7级黑暗城堡"))))
                .andExpect(jsonPath("$.logs[*]").value(Matchers.hasItem(Matchers.containsString("게임 용어집 적용"))))
                .andExpect(jsonPath("$.logs[*]").value(Matchers.hasItem(Matchers.containsString("파일 반영: 용어 4개 삭제 · 문장 4행을 일반 행으로 (그중 용어 불일치 1행"))));

        // 게임 용어집: 변형은 사라지고 기본 단어만 남는다. 새 기본 단어는 변형들의 유형을 물려받는다.
        // 홀로 남는 변형(7级黑暗城堡)은 그대로 둔다.
        assertThat(gameSrcs()).containsExactlyInAnyOrder("星英雄", "钻石", "钻石秘境", "7级黑暗城堡");
        mvc.perform(get("/api/games/{id}/terms", gameId))
                .andExpect(jsonPath("$[?(@.srcText=='钻石秘境')].ko").value("다이아 비경"))
                .andExpect(jsonPath("$[?(@.srcText=='钻石秘境')].type").value("PLACE"));
        // 기본 단어 번역을 만들 때 그 안에 든 짧은 용어(钻石)는 고정값으로 넘어간다
        assertThat(fake.suggestFixedParts).hasSize(1);
        assertThat(fake.suggestFixedParts.get(0)).containsEntry("钻石", "다이아");

        // 파일 용어에서도 변형이 지워진다. 새 기본 단어는 정리 끝에 게임 용어집을 적용하면서 이 파일의 확정 용어가 된다
        // (그래야 아래 용어 불일치 표시를 검수의 용어 고치기가 실제로 고칠 수 있다).
        List<String> projectSrcs = JsonPath.parse(body("/api/projects/{id}/terms", pid)).read("$.terms[*].srcText");
        assertThat(projectSrcs).containsExactlyInAnyOrder("星英雄", "钻石", "钻石秘境", "7级黑暗城堡");

        // 변형이던 행은 번역을 그대로 둔 채 일반 행이 되고, 단어장의 기본 단어 번역이 안 들어 있으면 용어 불일치가 붙는다.
        // 단어장에 없는 钻石秘境은 구 용어 표에 확정돼 있어도 표시하지 않는다.
        String rows = body("/api/projects/{id}/segments", pid);
        assertThat(JsonPath.parse(rows).<List<Boolean>>read("$.rows[?(@.srcText=='11星英雄')].isTerm")).containsExactly(false);
        assertThat(JsonPath.parse(rows).<List<String>>read("$.rows[?(@.srcText=='11星英雄')].currentText")).containsExactly("11성 영웅");
        assertThat(JsonPath.parse(rows).<List<List<String>>>read("$.rows[?(@.srcText=='11星英雄')].termMiss").get(0)).isEmpty();
        assertThat(JsonPath.parse(rows).<List<String>>read("$.rows[?(@.srcText=='12星英雄')].currentText")).containsExactly("12성급 캐릭터");
        assertThat(JsonPath.parse(rows).<List<List<String>>>read("$.rows[?(@.srcText=='12星英雄')].termMiss").get(0)).containsExactly("星英雄→성 영웅");
        assertThat(JsonPath.parse(rows).<List<List<String>>>read("$.rows[?(@.srcText=='1级钻石秘境')].termMiss").get(0)).isEmpty();
        assertThat(JsonPath.parse(rows).<List<List<String>>>read("$.rows[?(@.srcText=='2级钻石秘境')].termMiss").get(0)).isEmpty();
        // 기본 단어 행(星英雄)은 용어 행 그대로. 건너뛴 변형(7级黑暗城堡)도 용어 행 그대로 남는다.
        assertThat(JsonPath.parse(rows).<List<Boolean>>read("$.rows[?(@.srcText=='星英雄')].isTerm")).containsExactly(true);
        assertThat(JsonPath.parse(rows).<List<Boolean>>read("$.rows[?(@.srcText=='7级黑暗城堡')].isTerm")).containsExactly(true);
        // 지운 변형을 문장 안에 품은 행도 단어장으로 다시 검사한다: 星英雄은 들어 있고(성 영웅), 钻石秘境은 단어장에 없다
        assertThat(JsonPath.parse(rows).<List<List<String>>>read("$.rows[?(@.srcText=='" + DESC + "')].termMiss").get(0)).isEmpty();

        // 다시 돌리면 접을 게 없다
        mvc.perform(post("/api/games/{id}/terms/collapse", gameId))
                .andExpect(jsonPath("$.logs[*]").value(Matchers.hasItem(Matchers.containsString("숫자 변형이 없습니다"))));
    }

    @Test
    void keepsAGroupWhoseStemGptCouldNotTranslate() throws Exception {
        // 钻石秘境 후보를 안 준다 → 그 그룹은 그대로, 星英雄 그룹만 접힌다
        mvc.perform(post("/api/games/{id}/terms/collapse", gameId))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.logs[*]").value(Matchers.hasItem(Matchers.containsString("만들지 못함: 钻石秘境"))));
        assertThat(gameSrcs()).containsExactlyInAnyOrder("星英雄", "钻石", "1级钻石秘境", "2级钻石秘境", "7级黑暗城堡");
    }

    @Test
    void refusesWhileAnotherJobRunsOnTheGame() throws Exception {
        TranslationJob running = jobs.createForGame(gameId, TranslationJob.Kind.COLLAPSE_TERMS);
        mvc.perform(post("/api/games/{id}/terms/collapse", gameId)).andExpect(status().isConflict());
        jobs.finish(running.getId(), TranslationJob.Status.STOPPED);
    }
}
