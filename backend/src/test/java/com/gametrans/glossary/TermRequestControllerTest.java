package com.gametrans.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.TestWorkbooks;
import com.gametrans.llm.LlmClient;
import com.gametrans.llm.LlmClient.Reference;
import com.gametrans.project.ProjectRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class TermRequestControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired JdbcTemplate jdbc;
    @Autowired LlmClient llm;
    FakeLlmClient fake;

    @BeforeEach
    void setUp() {
        fake = (FakeLlmClient) llm;
        fake.termDrafts.clear();
        fake.draftCalls.clear();
    }

    Long game() throws Exception {
        long pid = TestProjects.uploadVariants(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE));
        return projects.findById(pid).orElseThrow().getGame().getId();
    }

    /** 角色 陷阱, 其他 每日, 怪物 暗影狼王(5행이라 예시가 아니다), 货币 金. */
    static byte[] form() {
        return TestWorkbooks.termRequestForm(new String[][]{
            {"陷阱", null, null, null, null, null, null},
            {null, null, null, null, null, null, "每日"},
            {null, null, null, null, "暗影狼王", null, null},
            {null, null, null, null, null, "金", null},
        });
    }

    ResultActions upload(Long gameId, String name, byte[] bytes) throws Exception {
        return mvc.perform(multipart("/api/games/{id}/glossary/requests", gameId)
                .file(new MockMultipartFile("file", name, "application/octet-stream", bytes)));
    }

    int requestsOf(Long gameId) {
        return jdbc.queryForObject("select count(*) from glossary_request where game_id = ?", Integer.class, gameId);
    }

    @Test
    void uploadDraftsFromCorpusAndLeavesTheRestForGpt() throws Exception {
        Long g = game();
        upload(g, "术语表.xlsx", form())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value(g))
                .andExpect(jsonPath("$.fileName").value("术语表.xlsx"))
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].src").value("陷阱"))
                .andExpect(jsonPath("$.items[0].category").value("CHARACTER"))
                .andExpect(jsonPath("$.items[0].source").value("CORPUS"))
                .andExpect(jsonPath("$.items[0].status").value("READY"))
                .andExpect(jsonPath("$.items[0].renderings[0].ko").value("함정"))
                .andExpect(jsonPath("$.items[0].renderings[0].rows").value(4))
                .andExpect(jsonPath("$.items[0].renderings[0].exampleSrc").value("放置陷阱"))
                .andExpect(jsonPath("$.items[0].renderings[0].exampleKo").value("함정을 설치"))
                .andExpect(jsonPath("$.items[0].renderings[1].ko").value("트랩"))   // 표기 갈림 탭과 같은 판정
                .andExpect(jsonPath("$.items[0].renderings[1].rows").value(2))
                .andExpect(jsonPath("$.items[0].existingKo").isEmpty())
                .andExpect(jsonPath("$.items[1].src").value("每日"))
                .andExpect(jsonPath("$.items[1].category").value("OTHER"))
                // 일일·매일·하루에가 2행씩 셋으로 갈려 주 표기(절반 이상)가 없다 — 표기 갈림 탭도, 기존 표기 후보도 못 고른다
                .andExpect(jsonPath("$.items[1].source").value("GPT"))
                .andExpect(jsonPath("$.items[1].status").value("PENDING"))
                .andExpect(jsonPath("$.items[1].renderings").isEmpty())
                .andExpect(jsonPath("$.items[2].src").value("暗影狼王"))
                .andExpect(jsonPath("$.items[2].source").value("GPT"))
                .andExpect(jsonPath("$.items[2].status").value("PENDING"))
                .andExpect(jsonPath("$.items[2].renderings").isEmpty())
                .andExpect(jsonPath("$.items[3].src").value("金"))   // 1글자는 번역본과 대조하지 않는다
                .andExpect(jsonPath("$.items[3].source").value("GPT"))
                .andExpect(jsonPath("$.items[3].status").value("PENDING"))
                .andExpect(jsonPath("$.items[3].renderings").isEmpty());
    }

    @Test
    void storesTheUploadedFileAndReadsTheRequestBack() throws Exception {
        Long g = game();
        String body = upload(g, "术语表.xlsx", form()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = JsonPath.parse(body).read("$.id", Long.class);
        String key = jdbc.queryForObject("select storage_key from glossary_request where id = ?", String.class, id);
        assertThat(key).isEqualTo("glossary-requests/" + g + "/" + id + "/术语表.xlsx");
        mvc.perform(get("/api/glossary/requests/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.items[0].seq").value(0))
                .andExpect(jsonPath("$.items[3].src").value("金"));
        mvc.perform(get("/api/glossary/requests/{id}", 999999L)).andExpect(status().isNotFound());
    }

    @Test
    void listShowsRequestsNewestFirstWithCounts() throws Exception {
        Long g = game();
        upload(g, "first.xlsx", form()).andExpect(status().isOk());
        upload(g, "second.xlsx", form()).andExpect(status().isOk());
        mvc.perform(get("/api/games/{id}/glossary/requests", g))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fileName").value("second.xlsx"))
                .andExpect(jsonPath("$[1].fileName").value("first.xlsx"))
                .andExpect(jsonPath("$[0].total").value(4))
                .andExpect(jsonPath("$[0].approved").value(0))
                .andExpect(jsonPath("$[0].pending").value(3));
    }

    @Test
    void itemShowsExistingGlossaryEntry() throws Exception {
        Long g = game();
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"SRC\",\"matchMode\":\"PARTIAL\",\"find\":\"陷阱\",\"ko\":\"트랩\"}")).andExpect(status().isOk());
        upload(g, "术语表.xlsx", form())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].src").value("陷阱"))
                .andExpect(jsonPath("$.items[0].existingKo").value("트랩"))
                .andExpect(jsonPath("$.items[1].existingKo").isEmpty());
    }

    @Test
    void uploadRejectsNonForm() throws Exception {
        Long g = game();
        upload(g, "Variants.xlsx", TestWorkbooks.variantRows())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("단어장 요청양식이 아닙니다: 2행에 角色·道具·技能·地点·怪物·货币·其他 머리글이 없습니다."));
        assertThat(requestsOf(g)).isZero();
    }

    @Test
    void uploadRejectsCorruptFile() throws Exception {
        Long g = game();
        byte[] junk = new byte[2048];
        new Random(7).nextBytes(junk);
        upload(g, "broken.xlsx", junk)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("단어장 요청양식이 아닙니다: 2행에 角色·道具·技能·地点·怪物·货币·其他 머리글이 없습니다."));
        assertThat(requestsOf(g)).isZero();
    }

    @Test
    void uploadRejectsUnknownGame() throws Exception {
        upload(999999L, "术语表.xlsx", form())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("게임을 찾을 수 없습니다: 999999"));
        assertThat(requestsOf(999999L)).isZero();
    }

    @Test
    void uploadRejectsFormWithNoTerms() throws Exception {
        Long g = game();
        upload(g, "术语表.xlsx", TestWorkbooks.termRequestForm(new String[0][]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("양식에 용어가 없습니다."));
        assertThat(requestsOf(g)).isZero();
    }

    /** 올린 요청의 id와 항목 id들(seq 순). */
    record Uploaded(long id, java.util.List<Long> items) {}

    Uploaded uploadForm(Long gameId) throws Exception {
        String body = upload(gameId, "术语表.xlsx", form()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.util.List<Number> ids = JsonPath.parse(body).read("$.items[*].id");
        return new Uploaded(JsonPath.parse(body).read("$.id", Long.class), ids.stream().map(Number::longValue).toList());
    }

    ResultActions approve(long requestId, String json) throws Exception {
        return mvc.perform(post("/api/glossary/requests/{id}/approve", requestId).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    java.util.Map<String, Object> entryOf(Long gameId, String find) {
        return jdbc.queryForMap("select id, ko, note, match_mode from glossary where game_id = ? and kind = 'SRC' and find = ?", gameId, find);
    }

    @Test
    void approvingUnifiesAndAddsEntryWithCategory() throws Exception {
        Long g = game();
        Uploaded r = uploadForm(g);
        approve(r.id(), "{\"items\":[{\"itemId\":" + r.items().get(0) + ",\"ko\":\"함정\",\"replace\":[\"트랩\"]}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(1))
                .andExpect(jsonPath("$.applied").value(2))
                .andExpect(jsonPath("$.request.items[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.request.items[0].finalKo").value("함정"))
                .andExpect(jsonPath("$.request.items[0].existingKo").value("함정"));
        java.util.Map<String, Object> e = entryOf(g, "陷阱");
        assertThat(e.get("ko")).isEqualTo("함정");
        assertThat(e.get("note")).isNull();
        assertThat(e.get("match_mode")).isEqualTo("PARTIAL");
        assertThat(jdbc.queryForObject("select category from glossary_category where game_id = ? and src = ?", String.class, g, "陷阱"))
                .isEqualTo("CHARACTER");
        java.util.Map<String, Object> item = jdbc.queryForMap("select status, final_ko, approved_at from glossary_request_item where id = ?", r.items().get(0));
        assertThat(item.get("status")).isEqualTo("APPROVED");
        assertThat(item.get("final_ko")).isEqualTo("함정");
        assertThat(item.get("approved_at")).isNotNull();
    }

    @Test
    void approvingWithKeepSavesNote() throws Exception {
        Long g = game();
        Uploaded r = uploadForm(g);
        // 트랩은 바꾸지 않고 남긴다 → 메모 항목
        approve(r.id(), "{\"items\":[{\"itemId\":" + r.items().get(0) + ",\"ko\":\"함정\",\"replace\":[]}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(1))
                .andExpect(jsonPath("$.applied").value(0));
        java.util.Map<String, Object> e = entryOf(g, "陷阱");
        assertThat(e.get("ko")).isNull();
        assertThat(e.get("note")).isEqualTo("함정으로 통일, 트랩도 씀");
    }

    @Test
    void bulkApprovesSeveral() throws Exception {
        Long g = game();
        Uploaded r = uploadForm(g);
        // 暗影狼王은 GPT 초안을 기다리는 항목이지만 리뷰어가 직접 적은 표기로 승인할 수 있다
        approve(r.id(), "{\"items\":[{\"itemId\":" + r.items().get(0) + ",\"ko\":\"함정\",\"replace\":[\"트랩\"]},"
                + "{\"itemId\":" + r.items().get(2) + ",\"ko\":\"그림자 늑대왕\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(2))
                .andExpect(jsonPath("$.request.items[2].status").value("APPROVED"))
                .andExpect(jsonPath("$.request.items[2].finalKo").value("그림자 늑대왕"));
        assertThat(entryOf(g, "暗影狼王").get("ko")).isEqualTo("그림자 늑대왕");
        // 怪物 → glossary_category CHARACTER
        assertThat(jdbc.queryForObject("select category from glossary_category where game_id = ? and src = ?", String.class, g, "暗影狼王"))
                .isEqualTo("CHARACTER");
        mvc.perform(get("/api/games/{id}/glossary/requests", g)).andExpect(jsonPath("$[0].approved").value(2));
    }

    @Test
    void reapprovingOverwritesTheEntry() throws Exception {
        Long g = game();
        Uploaded r = uploadForm(g);
        approve(r.id(), "{\"items\":[{\"itemId\":" + r.items().get(0) + ",\"ko\":\"함정\",\"replace\":[\"트랩\"]}]}").andExpect(status().isOk());
        Object firstId = entryOf(g, "陷阱").get("id");
        // renderings는 올릴 때 그대로라 트랩도 바꿀 표기로 넘긴다(안 넘기면 남기는 표기로 보고 메모 항목이 된다)
        approve(r.id(), "{\"items\":[{\"itemId\":" + r.items().get(0) + ",\"ko\":\"덫\",\"replace\":[\"함정\",\"트랩\"]}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.items[0].finalKo").value("덫"));
        java.util.Map<String, Object> e = entryOf(g, "陷阱");
        assertThat(e.get("id")).isEqualTo(firstId);
        assertThat(e.get("ko")).isEqualTo("덫");
    }

    @Test
    void rejectsItemOfAnotherRequest() throws Exception {
        Long g = game();
        Uploaded a = uploadForm(g);
        Uploaded b = uploadForm(g);
        long foreign = b.items().get(0);
        approve(a.id(), "{\"items\":[{\"itemId\":" + a.items().get(0) + ",\"ko\":\"함정\",\"replace\":[\"트랩\"]},"
                + "{\"itemId\":" + foreign + ",\"ko\":\"함정\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이 요청의 항목이 아닙니다: " + foreign));
        // 앞 항목도 승인되지 않는다 — 전부 확인한 뒤에 승인한다
        assertThat(jdbc.queryForObject("select count(*) from glossary where game_id = ? and find = ?", Integer.class, g, "陷阱")).isZero();
    }

    @Test
    void rejectsBlankKoBeforeApprovingAny() throws Exception {
        Long g = game();
        Uploaded r = uploadForm(g);
        approve(r.id(), "{\"items\":[{\"itemId\":" + r.items().get(0) + ",\"ko\":\"함정\",\"replace\":[\"트랩\"]},"
                + "{\"itemId\":" + r.items().get(2) + ",\"ko\":\"  \"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("최종 표기를 입력하세요."));
        assertThat(jdbc.queryForObject("select count(*) from glossary where game_id = ? and find = ?", Integer.class, g, "陷阱")).isZero();
    }

    ResultActions generate(long requestId) throws Exception {
        return mvc.perform(post("/api/glossary/requests/{id}/generate", requestId));
    }

    @Test
    void generateFillsPendingItems() throws Exception {
        Long g = game();
        Uploaded r = uploadForm(g);
        fake.termDrafts.put("每日", "일일");
        fake.termDrafts.put("暗影狼王", "그림자 늑대왕");
        fake.termDrafts.put("金", "골드");
        generate(r.id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("READY"))      // 陷阱(CORPUS)은 그대로
                .andExpect(jsonPath("$.items[0].source").value("CORPUS"))
                .andExpect(jsonPath("$.items[0].gptKo").isEmpty())
                .andExpect(jsonPath("$.items[1].status").value("READY"))
                .andExpect(jsonPath("$.items[1].gptKo").value("일일"))
                .andExpect(jsonPath("$.items[2].status").value("READY"))
                .andExpect(jsonPath("$.items[2].gptKo").value("그림자 늑대왕"))
                .andExpect(jsonPath("$.items[2].gptReason").value("가짜"))
                .andExpect(jsonPath("$.items[3].status").value("READY"))
                .andExpect(jsonPath("$.items[3].gptKo").value("골드"));
        // 분류마다 한 번씩 묻는다
        assertThat(fake.draftCalls).extracting(LlmClient.TermDraftRequest::category)
                .containsExactlyInAnyOrder("OTHER", "MONSTER", "CURRENCY");
        assertThat(fake.draftCalls).allSatisfy(c -> assertThat(c.terms()).hasSize(1));
        assertThat(fake.draftCalls.stream().filter(c -> c.category().equals("MONSTER")).findFirst().orElseThrow().terms())
                .containsExactly("暗影狼王");
    }

    @Test
    void missingGptAnswersStayFailed() throws Exception {
        Long g = game();
        Uploaded r = uploadForm(g);
        fake.termDrafts.put("暗影狼王", "그림자 늑대왕");
        generate(r.id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[1].status").value("FAILED"))
                .andExpect(jsonPath("$.items[2].status").value("READY"))
                .andExpect(jsonPath("$.items[3].status").value("FAILED"))
                .andExpect(jsonPath("$.items[3].gptKo").isEmpty());
        // 다시 만들면 FAILED만 다시 묻고 READY는 건드리지 않는다
        fake.draftCalls.clear();
        fake.termDrafts.put("金", "골드");
        fake.termDrafts.put("暗影狼王", "다른 답");
        generate(r.id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[2].gptKo").value("그림자 늑대왕"))
                .andExpect(jsonPath("$.items[3].status").value("READY"))
                .andExpect(jsonPath("$.items[3].gptKo").value("골드"));
        assertThat(fake.draftCalls).extracting(LlmClient.TermDraftRequest::category).containsExactlyInAnyOrder("OTHER", "CURRENCY");
    }

    @Test
    void knownTermsIncludeCorpusRenderings() throws Exception {
        Long g = game();
        Uploaded r = uploadForm(g);
        generate(r.id()).andExpect(status().isOk());
        assertThat(fake.draftCalls).isNotEmpty();
        assertThat(fake.draftCalls.get(0).known()).contains(new Reference("陷阱", "함정"));
    }

    @Test
    void knownTermsIncludeApprovedItemsAndGameInstructionsAreSent() throws Exception {
        Long g = game();
        Uploaded r = uploadForm(g);
        approve(r.id(), "{\"items\":[{\"itemId\":" + r.items().get(1) + ",\"ko\":\"일일\"}]}").andExpect(status().isOk());
        generate(r.id()).andExpect(status().isOk());
        // 승인한 每日은 다시 묻지 않고 이미 정한 표기로 실린다
        assertThat(fake.draftCalls).extracting(LlmClient.TermDraftRequest::category).containsExactlyInAnyOrder("MONSTER", "CURRENCY");
        assertThat(fake.draftCalls.get(0).known()).contains(new Reference("每日", "일일"), new Reference("陷阱", "함정"));
        assertThat(fake.draftCalls.get(0).instructions()).isNotEmpty();
    }

    @Test
    void generateRejectsUnknownRequest() throws Exception {
        generate(999999L).andExpect(status().isNotFound());
    }
}
