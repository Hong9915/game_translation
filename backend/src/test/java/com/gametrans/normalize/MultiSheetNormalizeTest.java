package com.gametrans.normalize;

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
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 한 프로젝트에 시트를 여럿 설정한다. 시트 하나를 정규화해도 다른 시트의 세그먼트·수정본·담당·완료는
 * 그대로여야 한다 — 시트마다 사람이 붙어 며칠씩 검토하므로 한 번의 재정규화로 남의 일을 날리면 안 된다.
 */
class MultiSheetNormalizeTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired NormalizeConfigRepository configs;
    @Autowired RevisionService revisions;
    @Autowired com.gametrans.memory.MemoryService memory;
    @Autowired LlmClient llm;

    @Test
    void normalizesSheetsSeparatelyAndReportsPerSheetStats() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());

        normalize(pid, "Items").andExpect(jsonPath("$.stats.totalRows").value(3));
        normalize(pid, "Skills").andExpect(jsonPath("$.stats.totalRows").value(5));

        mvc.perform(get("/api/projects/{id}/normalize", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configs.length()").value(2))
                .andExpect(jsonPath("$.configs[0].config.sheetName").value("Items"))
                .andExpect(jsonPath("$.configs[0].stats.totalRows").value(3))
                .andExpect(jsonPath("$.configs[0].stats.normalized").value(3))
                .andExpect(jsonPath("$.configs[1].config.sheetName").value("Skills"))
                .andExpect(jsonPath("$.configs[1].stats.totalRows").value(2))
                .andExpect(jsonPath("$.stats.totalRows").value(5))
                .andExpect(jsonPath("$.total").value(5));

        // ?sheet=를 주면 미리보기는 그 시트만 — 행 번호가 겹쳐도 섞이지 않는다
        String skills = mvc.perform(get("/api/projects/{id}/normalize", pid).param("sheet", "Skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.configs.length()").value(2))
                .andExpect(jsonPath("$.preview[0].indexLabel").value("Skills!2"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> labels = JsonPath.parse(skills).read("$.preview[*].indexLabel");
        assertThat(labels).containsExactly("Skills!2", "Skills!3");

        // 설정하지 않은 시트를 물으면 빈 미리보기가 아니라 404 — 화면이 조용히 빈 표를 그리지 않게
        mvc.perform(get("/api/projects/{id}/normalize", pid).param("sheet", "없는시트")).andExpect(status().isNotFound());
    }

    /** 같은 시트를 두 번 설정해도 설정 행은 하나다(unique 위반 없이 덮어쓴다). */
    @Test
    void normalizingTheSameSheetTwiceKeepsOneConfigRow() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());
        normalize(pid, "Items");
        normalize(pid, "Items");

        assertThat(configs.findByProjectIdOrderByIdAsc(pid)).hasSize(1);
        assertThat(sheetSegments(pid, "Items")).hasSize(3);
        mvc.perform(get("/api/projects/{id}/normalize", pid)).andExpect(jsonPath("$.configs.length()").value(1));
    }

    /** 다른 시트를 다시 정규화해도 이 시트의 수정본·담당·완료는 그대로. 이 시트를 다시 정규화하면 이 시트만 초기화된다. */
    @Test
    void reNormalizingOneSheetLeavesTheOtherSheetsWorkAlone() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());
        normalize(pid, "Items");
        normalize(pid, "Skills");
        long rid = reviewer();
        long itemId = sheetSegments(pid, "Items").get(0).getId();
        long skillId = sheetSegments(pid, "Skills").get(0).getId();

        mvc.perform(put("/api/segments/{sid}/translation", itemId).header("X-Reviewer-Id", rid)
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"화염의 검\"}")).andExpect(status().isOk());
        assign(pid, itemId, rid);
        mvc.perform(put("/api/segments/{sid}/done", itemId).header("X-Reviewer-Id", rid)
                .contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}")).andExpect(status().isOk());

        normalize(pid, "Skills");

        Segment item = segments.findById(itemId).orElseThrow();
        assertThat(item.getAssigneeId()).isEqualTo(rid);
        assertThat(item.isDone()).isTrue();
        assertThat(revisions.current(itemId).map(TranslationRevision::getText)).contains("화염의 검");
        assertThat(segments.findById(skillId)).isEmpty();          // Skills만 새로 만들어졌다
        assertThat(sheetSegments(pid, "Skills")).hasSize(2);
        assertThat(sheetSegments(pid, "Items")).hasSize(3);

        // 반대 방향: Items를 다시 정규화하면 Items만 초기화되고 Skills의 담당은 남는다
        long newSkillId = sheetSegments(pid, "Skills").get(0).getId();
        assign(pid, newSkillId, rid);

        normalize(pid, "Items");

        assertThat(segments.findById(itemId)).isEmpty();
        assertThat(sheetSegments(pid, "Items")).hasSize(3)
                .allSatisfy(s -> { assertThat(s.getAssigneeId()).isNull(); assertThat(s.isDone()).isFalse(); });
        assertThat(segments.findById(newSkillId).orElseThrow().getAssigneeId()).isEqualTo(rid);
    }

    /**
     * 용어는 프로젝트 전체에 걸쳐 있다. 시트가 여럿일 때 한 시트를 다시 정규화해도 지우면 안 되고,
     * 새로 만든 그 시트의 행에도 확정 용어가 다시 붙어야 한다 — 안 붙으면 그 행이 다시 GPT로 가고
     * 용어 행 수도 어긋난다.
     */
    @Test
    void reNormalizingOneOfTwoSheetsKeepsTheProjectTermsAndRelinksItsRows() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());
        normalize(pid, "Items");
        normalize(pid, "Skills");
        long termId = confirmTerm(pid, "霜语者", "서리속삭임");
        assertLinked(pid, "Items", termId, "서리속삭임");
        assertLinked(pid, "Skills", termId, "서리속삭임");

        normalize(pid, "Skills");

        mvc.perform(get("/api/projects/{id}/terms", pid))
                .andExpect(jsonPath("$.terms.length()").value(1))
                .andExpect(jsonPath("$.stats.confirmed").value(1))
                .andExpect(jsonPath("$.stats.termRows").value(2));
        assertLinked(pid, "Skills", termId, "서리속삭임");   // 새로 만든 행에 다시 붙었다
        assertLinked(pid, "Items", termId, "서리속삭임");
    }

    /** 용어를 확정한 뒤에 시트를 새로 추가해도 그 시트의 행에 확정 용어가 붙는다. */
    @Test
    void aSheetAddedAfterTermsWereConfirmedGetsItsRowsLinked() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());
        normalize(pid, "Items");
        long termId = confirmTerm(pid, "霜语者", "서리속삭임");

        normalize(pid, "Skills");

        assertLinked(pid, "Skills", termId, "서리속삭임");
        mvc.perform(get("/api/projects/{id}/terms", pid))
                .andExpect(jsonPath("$.terms.length()").value(1))
                .andExpect(jsonPath("$.stats.confirmed").value(1));
    }

    /**
     * 기존 한국어가 이미 있는 칸(EXISTING 유지)이라도 그 원문이 확정 용어면, 정규화 직후 현재 판은
     * 확정 용어 값(GLOSSARY)이다 — 용어를 확정할 때와 같은 동작이다. 파일에 있던 문장은 0판(EXISTING)으로
     * 이력에 남는다. 이 동작을 여기서 못 박아 둔다.
     */
    @Test
    void aKeptExistingKoRowStillTakesTheConfirmedTermValue() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());
        normalize(pid, "Items");
        long termId = confirmTerm(pid, "霜语者", "서리속삭임");

        // Quest!2의 KR_Name에는 이미 "서리"가 적혀 있다(그 원문이 방금 확정한 용어다)
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                        .content(TestProjects.QUEST_NORMALIZE))
                .andExpect(status().isOk());

        Segment s = sheetSegments(pid, "Quest").stream().filter(x -> "霜语者".equals(x.getSrcText())).findFirst().orElseThrow();
        assertThat(s.getExistingKoText()).isEqualTo("서리");
        assertThat(s.isTerm()).isTrue();
        assertThat(s.getTermId()).isEqualTo(termId);
        assertThat(revisions.current(s.getId())).get()
                .extracting(TranslationRevision::getAuthor, TranslationRevision::getText)
                .containsExactly(TranslationRevision.Author.GLOSSARY, "서리속삭임");
        // 파일에 있던 문장은 사라지지 않고 0판으로 남는다
        assertThat(revisions.bySegmentIds(List.of(s.getId())).get(s.getId()))
                .extracting(TranslationRevision::getAuthor, TranslationRevision::getText)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(TranslationRevision.Author.EXISTING, "서리"),
                        org.assertj.core.groups.Tuple.tuple(TranslationRevision.Author.GLOSSARY, "서리속삭임"));
    }

    /** 시트가 하나뿐인 프로젝트에서는 예전 그대로 — 다시 정규화하면 용어도 처음부터 다시 뽑는다. */
    @Test
    void reNormalizingTheOnlySheetStillClearsTheTerms() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());
        normalize(pid, "Items");
        confirmTerm(pid, "霜语者", "서리속삭임");

        normalize(pid, "Items");

        mvc.perform(get("/api/projects/{id}/terms", pid)).andExpect(jsonPath("$.terms.length()").value(0));
        assertThat(sheetSegments(pid, "Items")).allSatisfy(s -> assertThat(s.isTerm()).isFalse());
    }

    /** 시트 빼기: 설정과 그 시트 세그먼트만 사라진다. 시트 이름은 한글·공백·`%`·`#`이 있어도 오간다. */
    @Test
    void deleteRemovesOnlyThatSheetAndUnknownSheetIs404() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());
        normalize(pid, "Items");
        normalize(pid, "Skills");
        normalize(pid, "기타 시트");
        normalize(pid, "50%#특가");

        removeSheet(pid, "기타 시트").andExpect(status().isNoContent());
        removeSheet(pid, "50%#특가").andExpect(status().isNoContent());
        removeSheet(pid, "Skills").andExpect(status().isNoContent());

        assertThat(sheetSegments(pid, "Skills")).isEmpty();
        assertThat(sheetSegments(pid, "기타 시트")).isEmpty();
        assertThat(sheetSegments(pid, "50%#특가")).isEmpty();
        assertThat(sheetSegments(pid, "Items")).hasSize(3);
        mvc.perform(get("/api/projects/{id}/normalize", pid))
                .andExpect(jsonPath("$.configs.length()").value(1))
                .andExpect(jsonPath("$.configs[0].config.sheetName").value("Items"))
                .andExpect(jsonPath("$.stats.totalRows").value(3));

        removeSheet(pid, "Skills").andExpect(status().isNotFound());
    }

    /** 마지막 시트까지 빼면 정규화한 적 없는 프로젝트와 똑같이 보인다. 용어·메모리는 프로젝트 것이라 남는다. */
    @Test
    void removingTheLastSheetLeavesTheProjectAsIfNeverNormalized() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());
        long fresh = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());   // 정규화한 적 없는 프로젝트
        normalize(pid, "Items");
        confirmTerm(pid, "霜语者", "서리속삭임");

        removeSheet(pid, "Items").andExpect(status().isNoContent());

        String never = mvc.perform(get("/api/projects/{id}/normalize", fresh)).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String emptied = mvc.perform(get("/api/projects/{id}/normalize", pid)).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(emptied).isEqualTo(never);
        assertThat(segments.findByProjectIdOrderBySheetNameAscRowNoAscPairIndexAsc(pid)).isEmpty();
        // 용어와 게임 메모리는 프로젝트·게임 것이라 시트를 뺐다고 사라지지 않는다
        mvc.perform(get("/api/projects/{id}/terms", pid))
                .andExpect(jsonPath("$.terms.length()").value(1))
                .andExpect(jsonPath("$.stats.confirmed").value(1));
        assertThat(memory.allTerms(pid)).containsEntry("霜语者", "서리속삭임");
    }

    private org.springframework.test.web.servlet.ResultActions normalize(long pid, String sheet) throws Exception {
        return mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                        .content(TestProjects.sheetNormalize(sheet)))
                .andExpect(status().isOk());
    }

    private void assign(long pid, long segmentId, long reviewerId) throws Exception {
        mvc.perform(post("/api/projects/{id}/assign", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segmentIds\":[" + segmentId + "],\"assigneeId\":" + reviewerId + "}"))
                .andExpect(status().isOk());
    }

    private long reviewer() throws Exception {
        String name = "지민-" + UUID.randomUUID().toString().substring(0, 6);
        String body = mvc.perform(post("/api/reviewers").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.parse(body).read("$.id")).longValue();
    }

    private org.springframework.test.web.servlet.ResultActions removeSheet(long pid, String sheet) throws Exception {
        return mvc.perform(delete("/api/projects/{id}/normalize", pid).param("sheet", sheet));
    }

    /** 원문 하나를 용어로 뽑아 확정한다(TermControllerTest의 추출 → 확정 흐름). 용어 id를 돌려준다. */
    private long confirmTerm(long pid, String src, String ko) throws Exception {
        ((FakeLlmClient) llm).termsToReturn = List.of(new LlmClient.TermExtraction(src, "CHARACTER"));
        mvc.perform(post("/api/projects/{id}/terms/extract", pid)).andExpect(status().isOk());
        String list = mvc.perform(get("/api/projects/{id}/terms", pid)).andExpect(jsonPath("$.terms.length()").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long termId = ((Number) JsonPath.parse(list).read("$.terms[0].id")).longValue();
        mvc.perform(patch("/api/terms/{id}", termId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmedKo\":\"" + ko + "\",\"confirmed\":true}"))
                .andExpect(status().isOk());
        return termId;
    }

    /** 그 시트의 霜语者 행이 용어 행으로 붙어 있고 확정 번역이 GLOSSARY 리비전으로 들어가 있다. */
    private void assertLinked(long pid, String sheet, long termId, String ko) {
        Segment s = sheetSegments(pid, sheet).stream().filter(x -> "霜语者".equals(x.getSrcText())).findFirst().orElseThrow();
        assertThat(s.isTerm()).isTrue();
        assertThat(s.getTermId()).isEqualTo(termId);
        assertThat(revisions.current(s.getId())).get()
                .extracting(TranslationRevision::getAuthor, TranslationRevision::getText)
                .containsExactly(TranslationRevision.Author.GLOSSARY, ko);
    }

    private List<Segment> sheetSegments(long pid, String sheet) {
        return segments.findByProjectIdOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getSheetName().equals(sheet)).toList();
    }
}
