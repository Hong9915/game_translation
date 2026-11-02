package com.gametrans.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.llm.LlmClient;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 같은 게임 이름의 프로젝트끼리 확정 용어와 완성 번역을 다시 쓴다. */
class MemoryFlowTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;
    FakeLlmClient fake;
    String game;

    @BeforeEach
    void setUp() {
        fake = (FakeLlmClient) llm;
        fake.translateCalls.clear();
        fake.translations.clear();
        fake.dropOnce.clear();
        fake.breakPlaceholdersOnce.clear();
        game = "G-" + UUID.randomUUID().toString().substring(0, 8);  // 테스트 간 DB 공유 대비
    }

    private long segmentId(long pid, String indexLabel) throws Exception {
        String body = mvc.perform(get("/api/projects/{id}/segments", pid)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<?> ids = JsonPath.parse(body).read("$.rows[?(@.indexLabel=='" + indexLabel + "')].id", List.class);
        return ((Number) ids.get(0)).longValue();
    }

    @Test
    void exactMemoryMatchesAreFilledWithoutGptAndSimilarOnesBecomeReferences() throws Exception {
        long a = TestProjects.upload(mvc, game);
        TestProjects.normalize(mvc, a);
        mvc.perform(post("/api/projects/{id}/translate", a).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"))
                .andExpect(status().isOk());
        // 사람이 고친 문장 → 메모리
        mvc.perform(put("/api/segments/{id}/translation", segmentId(a, "ItemText!6")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"사용 후 HP {0} 회복, 쿨타임 {1}초\"}")).andExpect(status().isOk());
        // 내보내기 → 나머지 최종 문장도 메모리
        mvc.perform(post("/api/projects/{id}/export", a).contentType(MediaType.APPLICATION_JSON)
                .content("{\"addFlagColumn\":false,\"addHistorySheet\":false,\"addGlossarySheet\":false,\"ratio\":1.0}")).andExpect(status().isOk());
        fake.translateCalls.clear();

        long b = TestProjects.uploadVariant(mvc, game);
        // 정규화 시점에 이미 같은 게임의 결정(3·4행 원문과 동일한 a의 확정 번역)을 이어받는다(Task 15) —
        // 그래서 /translate 때는 그 두 행이 이미 번역돼 있어 "번역 메모리에서 N행 채움" 로그가 뜨지 않는다.
        TestProjects.normalize(mvc, b);
        mvc.perform(post("/api/projects/{id}/translate", b).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/projects/{id}/segments", b))
                .andExpect(jsonPath("$.rows[0].indexLabel").value("ItemText!2"))
                .andExpect(jsonPath("$.rows[0].currentText").value("KO:烈焰之弓"))
                .andExpect(jsonPath("$.rows[0].history[0].author").value("LLM"))
                .andExpect(jsonPath("$.rows[1].currentText").value("사용 후 HP {0} 회복, 쿨타임 {1}초"))
                .andExpect(jsonPath("$.rows[1].history[0].author").value("MEMORY"))
                .andExpect(jsonPath("$.rows[2].currentText").value("KO:霜语者"))
                .andExpect(jsonPath("$.rows[2].history[0].author").value("MEMORY"));

        assertThat(fake.translateCalls).hasSize(1);
        assertThat(fake.translateCalls.get(0).rows()).extracting(LlmClient.TranslateRow::index).containsExactly("ItemText!2");
        assertThat(fake.translateCalls.get(0).references()).extracting(LlmClient.Reference::cn, LlmClient.Reference::ko)
                .contains(org.assertj.core.groups.Tuple.tuple("烈焰之剑", "KO:烈焰之剑"));

        // 다른 게임은 메모리를 쓰지 않는다
        fake.translateCalls.clear();
        long c = TestProjects.uploadVariant(mvc, game + "-other");
        TestProjects.normalize(mvc, c);
        mvc.perform(post("/api/projects/{id}/translate", c).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"));
        assertThat(fake.translateCalls.get(0).rows()).hasSize(3);
        assertThat(fake.translateCalls.get(0).references()).isEmpty();
    }

    /**
     * TranslationService.fillFromMemory(번역 메모리 정확 일치를 GPT 없이 채우는 translate-time 경로)의 커버리지.
     * a를 통째로 지워도(cascade) translation_memory는 project_id가 아니라 game_name으로 저장돼 그대로 남는다 —
     * 그러면 이 게임 어떤 세그먼트도 이 원문에 대한 decided latest_ko를 갖지 않으므로, 정규화 때 이어받기
     * (SegmentRepository.findInheritableKo, Task 15)는 끼어들 수 없고 오직 fillFromMemory만 이 행을 채울 수 있다.
     */
    @Test
    void exactMemoryMatchIsFilledAtTranslateTimeWhenNoSegmentHasDecidedIt() throws Exception {
        long a = TestProjects.upload(mvc, game);
        TestProjects.normalize(mvc, a);
        // 사람이 고친 문장 → 메모리(게임 이름으로 저장, 세그먼트에 매이지 않는다)
        mvc.perform(put("/api/segments/{id}/translation", segmentId(a, "ItemText!6")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"사용 후 HP {0} 회복, 쿨타임 {1}초\"}")).andExpect(status().isOk());
        mvc.perform(delete("/api/projects/{id}", a)).andExpect(status().isNoContent());

        fake.translateCalls.clear();
        long b = TestProjects.uploadVariant(mvc, game);
        TestProjects.normalize(mvc, b); // 게임 안에 decided latest_ko를 가진 세그먼트가 없으니 이어받을 게 없다
        mvc.perform(post("/api/projects/{id}/translate", b).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs[*]").value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("메모리에서 1행"))));

        mvc.perform(get("/api/projects/{id}/segments", b))
                .andExpect(jsonPath("$.rows[?(@.indexLabel=='ItemText!3')].currentText").value("사용 후 HP {0} 회복, 쿨타임 {1}초"))
                .andExpect(jsonPath("$.rows[?(@.indexLabel=='ItemText!3')].history[0].author").value("MEMORY"));

        // 나머지 두 행(烈焰之弓·霜语者)은 메모리에 없으니 그대로 GPT로 간다
        assertThat(fake.translateCalls).hasSize(1);
        assertThat(fake.translateCalls.get(0).rows()).extracting(LlmClient.TranslateRow::index)
                .containsExactlyInAnyOrder("ItemText!2", "ItemText!4");
    }

    @Test
    void existingKoreanFromUploadedFilesFeedsTheMemoryWithoutOverridingHumanEdits() throws Exception {
        long a = TestProjects.uploadExistingKo(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", a).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE))
                .andExpect(status().isOk());
        mvc.perform(get("/api/games")).andExpect(jsonPath("$[?(@.name=='" + game + "')].memory").value(1));  // 정의의 심판

        // 사람이 고치면 메모리도 바뀐다
        mvc.perform(put("/api/segments/{id}/translation", segmentId(a, "Skill!2")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"정의의 재판\"}")).andExpect(status().isOk());
        // 같은 파일을 다시 올려 정규화해도(기존 번역 = 정의의 심판) 사람이 고친 메모리는 유지된다
        long b = TestProjects.uploadExistingKo(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", b).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE.replace("\"retranslateExisting\":false", "\"retranslateExisting\":true")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/projects/{id}/translate", b).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/projects/{id}/segments", b))
                .andExpect(jsonPath("$.rows[0].indexLabel").value("Skill!2"))
                .andExpect(jsonPath("$.rows[0].currentText").value("정의의 재판"))
                .andExpect(jsonPath("$.rows[0].history[0].author").value("MEMORY"));
    }

    /** 다른 게임에서 진행했던 프로젝트를 이 게임으로 옮기면, 완성 번역이 이 게임의 번역 메모리가 된다. */
    @Test
    void movingAProjectToAnotherGameSharesWhatItAlreadyHas() throws Exception {
        long a = TestProjects.upload(mvc, game + "-old");
        TestProjects.normalize(mvc, a);
        mvc.perform(post("/api/projects/{id}/translate", a).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/segments/{id}/translation", segmentId(a, "ItemText!6")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"사용 후 HP {0} 회복, 쿨타임 {1}초\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/games")).andExpect(jsonPath("$[?(@.name=='" + game + "')]").isEmpty());

        mvc.perform(patch("/api/projects/{id}", a).contentType(MediaType.APPLICATION_JSON).content("{\"gameName\":\"" + game + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameName").value(game));
        mvc.perform(get("/api/games"))
                .andExpect(jsonPath("$[?(@.name=='" + game + "')].memory").value(5));  // 번역된 5행

        long b = TestProjects.uploadVariant(mvc, game);
        TestProjects.normalize(mvc, b);
        mvc.perform(post("/api/projects/{id}/translate", b).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/projects/{id}/segments", b))
                .andExpect(jsonPath("$.rows[1].currentText").value("사용 후 HP {0} 회복, 쿨타임 {1}초"))
                .andExpect(jsonPath("$.rows[1].history[0].author").value("MEMORY"));
    }
}
