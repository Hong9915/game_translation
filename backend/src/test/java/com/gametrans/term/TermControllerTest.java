package com.gametrans.term;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.llm.LlmClient;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class TermControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;
    @Autowired SegmentRepository segments;
    @Autowired RevisionService revisions;
    @Autowired com.gametrans.translate.TranslationRevisionRepository revisionRepo;
    @Autowired com.gametrans.memory.GameTermRepository gameTerms;
    FakeLlmClient fake;

    @BeforeEach
    void setUp() {
        fake = (FakeLlmClient) llm;
        fake.extractFailure = null;
        fake.echoTypeInSrc = false;
        fake.unmatchedSrc = null;
        fake.termsToReturn = List.of(
                new LlmClient.TermExtraction("烈焰之剑", "ITEM"),
                new LlmClient.TermExtraction("霜语者", "CHARACTER"),
                new LlmClient.TermExtraction("不存在的词", "PLACE"));
        fake.candidatesToReturn.put("烈焰之剑", List.of(
                new LlmClient.Candidate("열염지검", "TRANSLITERATION"),
                new LlmClient.Candidate("화염의 검", "MEANING"),
                new LlmClient.Candidate("블레이징 소드", "MIXED")));
    }

    @Test
    void extractSuggestConfirmFlow() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);

        mvc.perform(post("/api/projects/{id}/terms/extract", pid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        String list = mvc.perform(get("/api/projects/{id}/terms", pid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.totalRows").value(5))
                .andExpect(jsonPath("$.stats.extracted").value(2))
                .andExpect(jsonPath("$.stats.termRows").value(2))
                .andExpect(jsonPath("$.stats.remainingRows").value(3))
                .andExpect(jsonPath("$.stats.confirmed").value(0))
                .andExpect(jsonPath("$.terms[?(@.srcText=='烈焰之剑')].occurrences").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        mvc.perform(post("/api/projects/{id}/terms/suggest", pid)).andExpect(status().isOk());
        mvc.perform(get("/api/projects/{id}/terms", pid))
                .andExpect(jsonPath("$.terms[?(@.srcText=='烈焰之剑')].candidates.length()").value(3))
                // 의역 후보가 확정 입력란 기본값으로 채워지되 아직 확정은 아니다
                .andExpect(jsonPath("$.terms[?(@.srcText=='烈焰之剑')].confirmedKo").value("화염의 검"))
                .andExpect(jsonPath("$.terms[?(@.srcText=='烈焰之剑')].confirmed").value(false))
                .andExpect(jsonPath("$.stats.confirmed").value(0));

        long termId = firstId(list, "$.terms[?(@.srcText=='烈焰之剑')].id");
        mvc.perform(patch("/api/terms/{id}", termId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmedKo\":\"화염의 검\",\"confirmed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.confirmed").value(true));

        Segment row2 = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).get(0);
        assertThat(row2.getIndexLabel()).isEqualTo("ItemText!2");
        assertThat(row2.isTerm()).isTrue();
        assertThat(revisions.current(row2.getId())).get()
                .extracting(TranslationRevision::getAuthor, TranslationRevision::getText)
                .containsExactly(TranslationRevision.Author.GLOSSARY, "화염의 검");

        long frostId = firstId(list, "$.terms[?(@.srcText=='霜语者')].id");
        mvc.perform(patch("/api/terms/{id}", frostId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmedKo\":\"서리속삭임\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/projects/{id}/terms/confirm-filled", pid))
                .andExpect(jsonPath("$.stats.confirmed").value(2));

        mvc.perform(get("/api/projects/{id}/terms", pid).param("unconfirmed", "true"))
                .andExpect(jsonPath("$.terms.length()").value(0));
        mvc.perform(get("/api/projects/{id}/terms", pid).param("q", "霜"))
                .andExpect(jsonPath("$.terms.length()").value(1));
    }

    @Test
    void listFiltersByType() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        mvc.perform(post("/api/projects/{id}/terms/extract", pid)).andExpect(status().isOk());

        mvc.perform(get("/api/projects/{id}/terms", pid).param("type", "CHARACTER"))
                .andExpect(jsonPath("$.terms.length()").value(1))
                .andExpect(jsonPath("$.terms[0].srcText").value("霜语者"))
                .andExpect(jsonPath("$.total").value(1))
                // 통계는 필터와 무관하게 프로젝트 전체 기준이다
                .andExpect(jsonPath("$.stats.extracted").value(2));
        // 유형 필터는 다른 필터와 AND로 겹친다
        mvc.perform(get("/api/projects/{id}/terms", pid).param("type", "CHARACTER").param("q", "烈焰"))
                .andExpect(jsonPath("$.terms.length()").value(0));
        mvc.perform(get("/api/projects/{id}/terms", pid).param("type", "DRAGON"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void confirmingWithoutTextIsRejectedAndUnconfirmUnlinks() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        mvc.perform(post("/api/projects/{id}/terms/extract", pid));
        String list = mvc.perform(get("/api/projects/{id}/terms", pid)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long termId = ((Number) JsonPath.parse(list).read("$.terms[0].id")).longValue();

        mvc.perform(patch("/api/terms/{id}", termId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmed\":true}")).andExpect(status().isBadRequest());

        mvc.perform(patch("/api/terms/{id}", termId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmedKo\":\"임시\",\"confirmed\":true}")).andExpect(status().isOk());
        mvc.perform(patch("/api/terms/{id}", termId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmed\":false}")).andExpect(status().isOk());
        assertThat(segments.countByProjectIdAndIsTermTrue(pid)).isZero();
    }

    @Test
    void reExtractKeepsConfirmedTerms() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        mvc.perform(post("/api/projects/{id}/terms/extract", pid));
        String list = mvc.perform(get("/api/projects/{id}/terms", pid)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long termId = firstId(list, "$.terms[?(@.srcText=='烈焰之剑')].id");
        mvc.perform(patch("/api/terms/{id}", termId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmedKo\":\"화염의 검\",\"confirmed\":true}"));

        fake.termsToReturn = List.of(new LlmClient.TermExtraction("霜语者", "CHARACTER"));
        mvc.perform(post("/api/projects/{id}/terms/extract", pid));
        mvc.perform(get("/api/projects/{id}/terms", pid))
                .andExpect(jsonPath("$.stats.extracted").value(2))
                .andExpect(jsonPath("$.stats.confirmed").value(1));
    }

    @Test
    void failedExtractionKeepsPreviouslyExtractedTerms() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);

        mvc.perform(post("/api/projects/{id}/terms/extract", pid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
        mvc.perform(get("/api/projects/{id}/terms", pid))
                .andExpect(jsonPath("$.stats.extracted").value(2));

        fake.extractFailure = new RuntimeException("boom");
        mvc.perform(post("/api/projects/{id}/terms/extract", pid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        mvc.perform(get("/api/projects/{id}/terms", pid))
                .andExpect(jsonPath("$.stats.extracted").value(2));
    }

    /** JsonPath.read(..., List.class) returns a raw List; capture it via a wildcard to keep .stream().map() type-safe. */
    private static long firstId(String json, String path) {
        List<?> ids = JsonPath.parse(json).read(path, List.class);
        return ids.stream().map(o -> ((Number) o).longValue()).findFirst().orElseThrow();
    }

    @Autowired TermRepository termRepo;

    /** 전체 확정: 후보만 있는 용어는 의역으로 채워 확정, 후보 없는 용어는 남긴다. 이미 적힌 값은 그대로 확정. */
    @Test
    void confirmAllFillsFromCandidatesAndSkipsTermsWithoutAny() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        Term withCands = new Term(); withCands.setProjectId(pid); withCands.setSrcText("烈焰之剑"); withCands.setType(Term.Type.ITEM);
        withCands.setCandidates(new java.util.ArrayList<>(List.of(new LlmClient.Candidate("맹렬한 불꽃의 검", "LITERAL"), new LlmClient.Candidate("화염의 검", "MEANING"))));
        Term typed = new Term(); typed.setProjectId(pid); typed.setSrcText("霜语者"); typed.setType(Term.Type.CHARACTER); typed.setConfirmedKo("서리속삭임");
        Term bare = new Term(); bare.setProjectId(pid); bare.setSrcText("传说"); bare.setType(Term.Type.OTHER);
        termRepo.saveAll(List.of(withCands, typed, bare));

        mvc.perform(post("/api/projects/{id}/terms/confirm-all", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.confirmed").value(2))
                .andExpect(jsonPath("$.terms[?(@.srcText=='烈焰之剑')].confirmedKo").value("화염의 검"))
                .andExpect(jsonPath("$.terms[?(@.srcText=='烈焰之剑')].confirmed").value(true))
                .andExpect(jsonPath("$.terms[?(@.srcText=='霜语者')].confirmed").value(true))
                .andExpect(jsonPath("$.terms[?(@.srcText=='传说')].confirmed").value(false));
        Segment row2 = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).get(0);
        assertThat(row2.isTerm()).isTrue();
        assertThat(revisions.current(row2.getId())).get().extracting(TranslationRevision::getText).isEqualTo("화염의 검");
    }

    /**
     * 일괄 확정은 행마다 쿼리를 날리지 않고 모아서 처리하지만, 결과는 하나씩 확정할 때와 같아야 한다:
     * 이미 같은 번역이 현재 판이면 새 판을 만들지 않고, 다르면 GLOSSARY 판을 하나 얹고, 게임 용어집에도 들어간다.
     */
    @Test
    void confirmAllKeepsMatchingRevisionAndAddsGlossaryVersionOtherwise() throws Exception {
        String game = TestProjects.freshGame();
        long pid = TestProjects.upload(mvc, game);
        TestProjects.normalize(mvc, pid);
        List<Segment> rows = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid);
        Segment sword = rows.stream().filter(s -> s.getSrcText().equals("烈焰之剑")).findFirst().orElseThrow();
        Segment frost = rows.stream().filter(s -> s.getSrcText().equals("霜语者")).findFirst().orElseThrow();
        revisions.add(sword.getId(), "옛 번역", TranslationRevision.Author.LLM);
        revisions.add(frost.getId(), "서리속삭임", TranslationRevision.Author.HUMAN);
        Term a = new Term(); a.setProjectId(pid); a.setSrcText("烈焰之剑"); a.setType(Term.Type.ITEM); a.setConfirmedKo("화염의 검");
        Term b = new Term(); b.setProjectId(pid); b.setSrcText("霜语者"); b.setType(Term.Type.CHARACTER); b.setConfirmedKo("서리속삭임 (의역: 서리의 속삭임)");
        termRepo.saveAll(List.of(a, b));

        mvc.perform(post("/api/projects/{id}/terms/confirm-all", pid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.confirmed").value(2));

        assertThat(revisionRepo.findBySegmentIdOrderByVersionAsc(sword.getId()))
                .extracting(TranslationRevision::getVersion, TranslationRevision::getAuthor, TranslationRevision::getText)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, TranslationRevision.Author.LLM, "옛 번역"),
                        org.assertj.core.groups.Tuple.tuple(1, TranslationRevision.Author.GLOSSARY, "화염의 검"));
        // 메모를 뗀 번역이 현재 판과 같으므로 새 판이 생기지 않는다
        assertThat(revisionRepo.findBySegmentIdOrderByVersionAsc(frost.getId())).hasSize(1);
        assertThat(segments.findById(sword.getId())).get().extracting(Segment::isTerm, Segment::getTermId).containsExactly(true, a.getId());
        assertThat(gameTerms.findByGameNameAndSrcTextIn(game, List.of("烈焰之剑", "霜语者")))
                .extracting(com.gametrans.memory.GameTerm::getSrcText, com.gametrans.memory.GameTerm::getKo)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("烈焰之剑", "화염의 검"),
                        org.assertj.core.groups.Tuple.tuple("霜语者", "서리속삭임 (의역: 서리의 속삭임)"));
    }

    @Test
    void termsListIsPaged() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        for (int i = 0; i < 3; i++) { Term t = new Term(); t.setProjectId(pid); t.setSrcText("용어" + i); t.setType(Term.Type.OTHER); t.setOccurrences(3 - i); termRepo.save(t); }
        mvc.perform(get("/api/projects/{id}/terms", pid).param("page", "1").param("size", "2"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.terms.length()").value(1))
                .andExpect(jsonPath("$.terms[0].srcText").value("용어2"))
                .andExpect(jsonPath("$.stats.extracted").value(3));
    }

    /** GPT가 못 뽑은 어휘를 사람이 직접 넣는다. 한국어까지 적으면 바로 확정되어 행·공용 용어집에 반영된다. */
    @Test
    void manualTermWithKoreanIsCreatedConfirmedAndLinked() throws Exception {
        long pid = TestProjects.upload(mvc, "G-manual-" + System.nanoTime());
        TestProjects.normalize(mvc, pid);
        mvc.perform(post("/api/projects/{id}/terms", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"srcText\":\"霜语者\",\"type\":\"COMMON\",\"confirmedKo\":\"서리속삭임\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.srcText").value("霜语者"))
                .andExpect(jsonPath("$.type").value("COMMON"))
                .andExpect(jsonPath("$.occurrences").value(1))
                .andExpect(jsonPath("$.confirmedKo").value("서리속삭임"))
                .andExpect(jsonPath("$.confirmed").value(true));
        // 4행(霜语者)은 용어 행이 되고 GLOSSARY 리비전이 붙는다
        Segment row4 = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).get(2);
        assertThat(row4.getIndexLabel()).isEqualTo("ItemText!4");
        assertThat(row4.isTerm()).isTrue();
        assertThat(revisions.current(row4.getId()).map(TranslationRevision::getText)).contains("서리속삭임");
        mvc.perform(get("/api/projects/{id}/terms", pid))
                .andExpect(jsonPath("$.stats.extracted").value(1))
                .andExpect(jsonPath("$.stats.confirmed").value(1));
        mvc.perform(get("/api/games")).andExpect(jsonPath("$[?(@.projects==1 && @.terms==1)]").isNotEmpty());
    }

    @Test
    void manualTermWithoutKoreanStaysUnconfirmedAndMayBeAbsentFromThisFile() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        mvc.perform(post("/api/projects/{id}/terms", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"srcText\":\"礼包\",\"type\":\"COMMON\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.occurrences").value(0))
                .andExpect(jsonPath("$.confirmed").value(false));
        mvc.perform(post("/api/projects/{id}/terms", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"srcText\":\" 礼包 \",\"type\":\"ITEM\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/projects/{id}/terms", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"srcText\":\"  \",\"type\":\"ITEM\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void extractionKeepsCommonVocabularyType() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        fake.termsToReturn = List.of(new LlmClient.TermExtraction("传说", "COMMON"));
        mvc.perform(post("/api/projects/{id}/terms/extract", pid)).andExpect(status().isOk());
        mvc.perform(get("/api/projects/{id}/terms", pid))
                .andExpect(jsonPath("$.terms[0].srcText").value("传说"))
                .andExpect(jsonPath("$.terms[0].type").value("COMMON"));
    }

    /** GPT가 11霜语者·12霜语者처럼 숫자만 다른 변형을 돌려줘도 용어는 기본 단어 霜语者 하나만 남는다. */
    @Test
    void extractionCollapsesNumberedVariantsToTheStem() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        fake.termsToReturn = List.of(
                new LlmClient.TermExtraction("11霜语者", "CHARACTER"),
                new LlmClient.TermExtraction("霜语者", "CHARACTER"),
                new LlmClient.TermExtraction("霜语者12", "CHARACTER"),
                new LlmClient.TermExtraction("烈焰之剑", "ITEM"));
        mvc.perform(post("/api/projects/{id}/terms/extract", pid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.logs[*]").value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("숫자 변형 2개는 기본 단어 1개로 접음"))));
        mvc.perform(get("/api/projects/{id}/terms", pid))
                .andExpect(jsonPath("$.stats.extracted").value(2))
                .andExpect(jsonPath("$.terms[?(@.srcText=='霜语者')].type").value("CHARACTER"))
                .andExpect(jsonPath("$.terms[?(@.srcText=='11霜语者')]").isEmpty())
                .andExpect(jsonPath("$.terms[?(@.srcText=='霜语者12')]").isEmpty());
    }

    /** gpt-5-mini가 가끔 src를 "英雄 (CHARACTER)"처럼 유형까지 붙여 돌려준다. 그래도 후보는 원래 용어에 붙어야 한다. */
    @Test
    void suggestionsMatchEvenWhenModelEchoesTheTypeSuffix() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        mvc.perform(post("/api/projects/{id}/terms/extract", pid)).andExpect(status().isOk());
        fake.echoTypeInSrc = true;
        mvc.perform(post("/api/projects/{id}/terms/suggest", pid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.logs[*]").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("불일치")))));
        mvc.perform(get("/api/projects/{id}/terms", pid))
                .andExpect(jsonPath("$.terms[?(@.srcText=='烈焰之剑')].candidates.length()").value(3))
                .andExpect(jsonPath("$.terms[?(@.srcText=='烈焰之剑')].confirmedKo").value("화염의 검"));
    }

    /** 응답의 src가 어떤 용어와도 맞지 않으면 조용히 버리지 말고 작업 로그에 남긴다. */
    @Test
    void unmatchedSuggestionsAreLogged() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        mvc.perform(post("/api/projects/{id}/terms/extract", pid)).andExpect(status().isOk());
        fake.candidatesToReturn.clear();
        fake.candidatesToReturn.put("烈焰之剑", List.of(new LlmClient.Candidate("화염의 검", "MEANING")));
        fake.unmatchedSrc = "전혀 다른 원문";
        mvc.perform(post("/api/projects/{id}/terms/suggest", pid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.logs[*]").value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("원문 불일치 1개"))));
    }

    /** 추출 전에 파일 전체에서 반복 명사구를 뽑아, 그 묶음에 등장하는 것만 빈도와 함께 GPT에 힌트로 준다. */
    @Test
    void extractPassesRepeatedPhrasesOfTheFileToGpt() throws Exception {
        String csv = "ID,CN,EN\n1,在商城购买礼包,Buy a pack in the shop\n2,商城限时折扣,Shop sale\n3,礼包限时折扣,Pack sale\n4,英雄升级,Hero up\n";
        org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile("file", "shop.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/projects").file(file).param("gameName", TestProjects.freshGame()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long pid = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sheetName\":\"shop\",\"headerRow\":1,\"keyCol\":\"A\",\"pairs\":[{\"srcCol\":\"B\",\"enCol\":\"C\",\"koColMode\":\"NEW\",\"koHeader\":\"KO\"}]}"))
                .andExpect(status().isOk());
        fake.termsToReturn = List.of(new LlmClient.TermExtraction("商城", "COMMON"));

        mvc.perform(post("/api/projects/{id}/terms/extract", pid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.logs[*]").value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("반복 구절 후보 3개"))));
        assertThat(fake.lastFrequent).containsEntry("商城", 2).containsEntry("礼包", 2).containsEntry("限时折扣", 2).doesNotContainKey("英雄升级").doesNotContainKey("限时");
        mvc.perform(get("/api/projects/{id}/terms", pid))
                .andExpect(jsonPath("$.terms[0].srcText").value("商城"))
                .andExpect(jsonPath("$.terms[0].occurrences").value(2));
    }

    /** 짧은 용어를 먼저 추천받고, 그 의역을 긴 용어 추천의 고정값으로 넘긴다. */
    @Test
    void compoundTermsAreSuggestedAfterTheirPartsWithThePartsFixed() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        fake.termsToReturn = List.of(new LlmClient.TermExtraction("烈焰之剑", "ITEM"), new LlmClient.TermExtraction("烈焰", "COMMON"));
        fake.candidatesToReturn.put("烈焰", List.of(new LlmClient.Candidate("화염", "MEANING")));
        mvc.perform(post("/api/projects/{id}/terms/extract", pid)).andExpect(status().isOk());
        fake.suggestFixedParts.clear();
        mvc.perform(post("/api/projects/{id}/terms/suggest", pid)).andExpect(status().isOk());
        assertThat(fake.suggestFixedParts).hasSize(2);
        assertThat(fake.suggestFixedParts.get(0)).isEmpty();
        assertThat(fake.suggestFixedParts.get(1)).containsExactly(java.util.Map.entry("烈焰", "화염"));
    }

    /** 문장부호·자리표시자가 있거나, 다른 용어를 품고 늘 행 전체로만 나오는 일반 어휘는 용어가 아니다. 고유명사는 남긴다. */
    @Test
    void wholeStringsAreNotTerms() {
        java.util.function.Function<String, com.gametrans.normalize.Segment> row = t -> { var s = new com.gametrans.normalize.Segment(); s.setSrcText(t); return s; };
        List<com.gametrans.normalize.Segment> rows = List.of(row.apply("能量石已满星"), row.apply("能量石"), row.apply("该能量石已锁定"), row.apply("升星券"), row.apply("使用升星券可以升星"));
        List<String> others = List.of("能量石", "升星", "升星券", "能量石已满星");
        assertThat(TermService.isWholeString("能量石已满星", Term.Type.COMMON, others, rows)).isTrue();
        assertThat(TermService.isWholeString("恢复{0}点", Term.Type.COMMON, others, rows)).isTrue();
        assertThat(TermService.isWholeString("能量石", Term.Type.ITEM, others, rows)).isFalse();
        // 다른 문장 안에서도 나오는 복합어는 용어로 남는다
        assertThat(TermService.isWholeString("升星券", Term.Type.COMMON, others, rows)).isFalse();
        // 고유명사는 행 전체여도 용어
        assertThat(TermService.isWholeString("能量石已满星", Term.Type.ITEM, others, rows)).isFalse();
    }

    /** 첫 파일에서 확정한 용어 두 개(행 전체인 것, 문장 안에 든 것)를 같은 게임의 공용 용어집에 넣는다. */
    private String gameWithTwoTerms() throws Exception {
        String game = TestProjects.freshGame();
        long first = TestProjects.upload(mvc, game);
        TestProjects.normalize(mvc, first);
        for (String body : List.of("{\"srcText\":\"霜语者\",\"type\":\"CHARACTER\",\"confirmedKo\":\"서리속삭임\"}",
                "{\"srcText\":\"生命值\",\"type\":\"COMMON\",\"confirmedKo\":\"HP\"}",
                "{\"srcText\":\"烈焰之剑\",\"type\":\"ITEM\",\"confirmedKo\":\"화염의 검\"}"))
            mvc.perform(post("/api/projects/{id}/terms", first).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        return game;
    }

    /** 게임 용어집은 새 파일에서 다시 확정하지 않아도 번역에 적용된다(용어 추출을 건너뛰어도). */
    @Test
    void gameGlossaryIsAppliedToANewFileWhenTranslationStarts() throws Exception {
        long pid = TestProjects.uploadVariant(mvc, gameWithTwoTerms());
        TestProjects.normalize(mvc, pid);
        fake.translateCalls.clear();
        fake.translations.put("使用后恢复{0}点生命值，冷却{1}秒。", "사용 시 HP {0} 회복. 재사용 대기 {1}초.");
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"))
                .andExpect(status().isOk());

        // 이 파일에 나오는 두 용어만 확정 용어로 들어온다(烈焰之剑은 이 파일에 없다)
        mvc.perform(get("/api/projects/{id}/terms", pid))
                .andExpect(jsonPath("$.terms.length()").value(2))
                .andExpect(jsonPath("$.terms[?(@.srcText=='生命值')].confirmed").value(true))
                .andExpect(jsonPath("$.terms[?(@.srcText=='霜语者')].confirmedKo").value("서리속삭임"));
        Segment frost = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getSrcText().equals("霜语者")).findFirst().orElseThrow();
        assertThat(frost.isTerm()).isTrue();
        assertThat(revisions.current(frost.getId()).map(TranslationRevision::getAuthor)).contains(TranslationRevision.Author.GLOSSARY);
        assertThat(fake.translateCalls).anySatisfy(c -> assertThat(c.glossary()).containsEntry("生命值", "HP"));
        assertThat(fake.translateCalls).allSatisfy(c -> assertThat(c.rows()).noneMatch(r -> r.cn().equals("霜语者")));
    }

    /** 추출에서 GPT가 뽑은 용어가 게임 용어집에 있으면 바로 확정되고, GPT가 못 뽑은 게임 용어도 같이 들어온다. */
    @Test
    void extractionConfirmsGameGlossaryTermsWithoutAHuman() throws Exception {
        long pid = TestProjects.uploadVariant(mvc, gameWithTwoTerms());
        TestProjects.normalize(mvc, pid);
        fake.termsToReturn = List.of(new LlmClient.TermExtraction("霜语者", "CHARACTER"), new LlmClient.TermExtraction("烈焰之弓", "ITEM"));
        mvc.perform(post("/api/projects/{id}/terms/extract", pid)).andExpect(status().isOk());

        mvc.perform(get("/api/projects/{id}/terms", pid))
                .andExpect(jsonPath("$.terms.length()").value(3))
                .andExpect(jsonPath("$.stats.confirmed").value(2))
                .andExpect(jsonPath("$.terms[?(@.srcText=='霜语者')].confirmed").value(true))
                .andExpect(jsonPath("$.terms[?(@.srcText=='生命值')].confirmed").value(true))
                .andExpect(jsonPath("$.terms[?(@.srcText=='烈焰之弓')].confirmed").value(false));
    }
}
