package com.gametrans.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.glossary.GlossaryEntry.Kind;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.ProjectRepository;
import com.gametrans.translate.RevisionService;
import com.jayway.jsonpath.JsonPath;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class GlossaryControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired SegmentRepository segments;
    @Autowired RevisionService revisions;
    /** 사전 확인과 실제 유니크 인덱스 위반 사이의 레이스를 스레드 없이 재현하기 위해서만 감싼다(Ruling 28). */
    @SpyBean GlossaryEntryRepository glossaryRepo;

    private Long gameOfNewProject() throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));
        return projects.findById(pid).orElseThrow().getGame().getId();
    }

    private String body(String kind, String mode, String find, String ko) {
        return "{\"kind\":\"" + kind + "\",\"matchMode\":\"" + mode + "\",\"find\":\"" + find + "\",\"ko\":\"" + ko + "\"}";
    }

    private String bodyWithOldKo(String kind, String mode, String find, String ko, String oldKo) {
        return "{\"kind\":\"" + kind + "\",\"matchMode\":\"" + mode + "\",\"find\":\"" + find + "\",\"ko\":\"" + ko + "\",\"oldKo\":\"" + oldKo + "\"}";
    }

    private Long gameOfUpgradeProject() throws Exception {
        long pid = TestProjects.uploadUpgrade(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));
        return projects.findById(pid).orElseThrow().getGame().getId();
    }

    @Test
    void addingAnEntryAppliesItRightAway() throws Exception {
        Long g = gameOfNewProject();
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                        .content(body("KO", "PARTIAL", "심판", "판결")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.entry.shared").value(false));
    }

    @Test
    void addingAnEntryWithNullKoIncludesKoAsNullInJson() throws Exception {
        Long g = gameOfNewProject();
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"KO\",\"matchMode\":\"PARTIAL\",\"find\":\"심판\",\"ko\":null}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"ko\":null")))
                .andExpect(jsonPath("$.entry.shared").value(false));
    }

    @Test
    void countPreviewMatchesTheAppliedCount() throws Exception {
        Long g = gameOfNewProject();
        mvc.perform(get("/api/games/{id}/glossary/apply/count", g)
                        .param("kind", "KO").param("matchMode", "PARTIAL").param("find", "심판").param("ko", "판결"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void addingTheSameKindAndFindOverwrites() throws Exception {
        Long g = gameOfNewProject();
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                .content(body("SRC", "WHOLE", "正义裁决", "정의의 판결")));
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                        .content(body("SRC", "WHOLE", "正义裁决", "정의의 심결")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/games/{id}/glossary", g).param("scope", "GAME"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].ko").value("정의의 심결"));
    }

    @Test
    void sharingCopiesTheEntryToTheSharedGlossary() throws Exception {
        Long g = gameOfNewProject();
        String saved = mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                .content(body("SRC", "PARTIAL", "红包" + System.nanoTime(), "행운 봉투"))).andReturn().getResponse().getContentAsString();
        Long id = JsonPath.parse(saved).read("$.entry.id", Long.class);

        mvc.perform(post("/api/glossary/{id}/share", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shared").value(true))
                .andExpect(jsonPath("$.gameId").doesNotExist());

        // 게임 전용 항목은 그대로 남는다
        mvc.perform(get("/api/games/{id}/glossary", g).param("scope", "GAME"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void gameScopedEntryWinsOverTheSharedOneWithTheSameKey() throws Exception {
        Long g = gameOfNewProject();
        String find = "红包" + System.nanoTime();
        String saved = mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                .content(body("SRC", "PARTIAL", find, "공유 값"))).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/glossary/{id}/share", JsonPath.parse(saved).read("$.entry.id", Long.class)));
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                .content(body("SRC", "PARTIAL", find, "이 게임 값")));

        mvc.perform(get("/api/games/{id}/glossary", g).param("scope", "ALL"))
                .andExpect(jsonPath("$[?(@.find=='" + find + "')].ko").value(org.hamcrest.Matchers.contains("이 게임 값")));
    }

    @Test
    void deletingAnEntryDoesNotUndoTheFixes() throws Exception {
        Long g = gameOfNewProject();
        String saved = mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                .content(body("KO", "PARTIAL", "심판", "판결"))).andReturn().getResponse().getContentAsString();
        Long id = JsonPath.parse(saved).read("$.entry.id", Long.class);

        mvc.perform(delete("/api/glossary/{id}", id)).andExpect(status().isNoContent());

        mvc.perform(get("/api/games/{id}/glossary", g).param("scope", "GAME")).andExpect(jsonPath("$.length()").value(0));
        // Fix는 "정의의 판결" 그대로다 — 치환은 되돌리지 않는다
    }

    @Test
    void applyAllReappliesEveryEntry() throws Exception {
        Long g = gameOfNewProject();
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                .content(body("KO", "PARTIAL", "심판", "판결")));
        // 이미 적용돼서 두 번째는 0건
        mvc.perform(post("/api/games/{id}/glossary/apply", g))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(0));
    }

    // ---- Ruling 26: PUT ----

    @Test
    void updatingAGameScopedEntryAppliesItRightAway() throws Exception {
        Long g = gameOfNewProject();
        // ko가 비어 있으면 메모 전용 항목이라 저장 시점엔 아무 것도 안 바뀐다.
        String saved = mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                .content(body("KO", "PARTIAL", "심판", ""))).andReturn().getResponse().getContentAsString();
        Long id = JsonPath.parse(saved).read("$.entry.id", Long.class);

        mvc.perform(put("/api/glossary/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(body("KO", "PARTIAL", "심판", "판결")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.entry.ko").value("판결"));
    }

    @Test
    void updatingASharedEntryReturnsZeroApplied() throws Exception {
        Long g = gameOfNewProject();
        String find = "红包" + System.nanoTime();
        String saved = mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                .content(body("SRC", "WHOLE", find, "행운 봉투"))).andReturn().getResponse().getContentAsString();
        Long gameScopedId = JsonPath.parse(saved).read("$.entry.id", Long.class);
        String shared = mvc.perform(post("/api/glossary/{id}/share", gameScopedId))
                .andReturn().getResponse().getContentAsString();
        Long sharedId = JsonPath.parse(shared).read("$.id", Long.class);

        mvc.perform(put("/api/glossary/{id}", sharedId).contentType(MediaType.APPLICATION_JSON)
                        .content(body("SRC", "WHOLE", find, "새 번역")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(0))
                .andExpect(jsonPath("$.entry.ko").value("새 번역"));
    }

    @Test
    void updatingToAKeyThatAlreadyExistsInTheSameScopeIsRejected() throws Exception {
        Long g = gameOfNewProject();
        String findA = "A" + System.nanoTime();
        String findB = "B" + System.nanoTime();
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                .content(body("KO", "PARTIAL", findA, "값1")));
        String savedB = mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                .content(body("KO", "PARTIAL", findB, "값2"))).andReturn().getResponse().getContentAsString();
        Long idB = JsonPath.parse(savedB).read("$.entry.id", Long.class);

        mvc.perform(put("/api/glossary/{id}", idB).contentType(MediaType.APPLICATION_JSON)
                        .content(body("KO", "PARTIAL", findA, "값2")))
                .andExpect(status().isBadRequest());
    }

    // ---- Ruling 27: 404 / 400 ----

    @Test
    void updatingAnUnknownIdReturns404() throws Exception {
        mvc.perform(put("/api/glossary/{id}", 999_999_999L).contentType(MediaType.APPLICATION_JSON)
                        .content(body("KO", "PARTIAL", "a", "b")))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingAnUnknownIdReturns404() throws Exception {
        mvc.perform(delete("/api/glossary/{id}", 999_999_999L)).andExpect(status().isNotFound());
    }

    @Test
    void sharingAnUnknownIdReturns404() throws Exception {
        mvc.perform(post("/api/glossary/{id}/share", 999_999_999L)).andExpect(status().isNotFound());
    }

    @Test
    void savingWithAnInvalidKindReturns400() throws Exception {
        Long g = gameOfNewProject();
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                        .content(body("NOPE", "PARTIAL", "a", "b")))
                .andExpect(status().isBadRequest());
    }

    // ---- Ruling 11: applyAll이 앞선 항목의 쓰기를 같은 트랜잭션 안에서 본다 ----

    @Test
    void applyAllSeesEarlierEntriesWritesWithinTheSameTransaction() throws Exception {
        String game = TestProjects.freshGame();
        long pid1 = TestProjects.uploadExistingKo(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", pid1).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));
        Long g = projects.findById(pid1).orElseThrow().getGame().getId();

        // 두 항목을 저장한다. 각 POST는 바로 적용되지만 이 시점엔 pid1의 행만 있다 — pid2는 아직 없다.
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                .content(body("KO", "PARTIAL", "심판", "판결")));
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                .content(body("KO", "PARTIAL", "판결", "결정")));

        // 같은 게임에 새 파일을 올린다. 이 행의 Fix는 "정의의 심판" 그대로 — 두 항목 모두 아직 안 걸렸다.
        long pid2 = TestProjects.uploadExistingKo(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", pid2).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));

        // 한 트랜잭션 안에서 "심판"→"판결"이 적용된 뒤, 바로 이어서 "판결"→"결정"까지 걸려야 한다.
        // flush/clear가 없으면 두 번째 항목이 첫 번째가 방금 쓴 값을 못 보고 건너뛴다.
        mvc.perform(post("/api/games/{id}/glossary/apply", g))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(2));

        Segment row2 = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid2).stream()
                .filter(s -> s.getRowNo() == 2).findFirst().orElseThrow();
        assertThat(revisions.current(row2.getId()).map(r -> r.getText()).orElse(null)).isEqualTo("정의의 결정");
    }

    // ---- Important finding 1: count는 upsert와 같은 방식으로 find를 다듬는다 ----

    @Test
    void countTrimsFindLikeUpsertDoes() throws Exception {
        Long g = gameOfNewProject();
        String trimmed = mvc.perform(get("/api/games/{id}/glossary/apply/count", g)
                        .param("kind", "KO").param("matchMode", "PARTIAL").param("find", "심판").param("ko", "판결"))
                .andReturn().getResponse().getContentAsString();
        String padded = mvc.perform(get("/api/games/{id}/glossary/apply/count", g)
                        .param("kind", "KO").param("matchMode", "PARTIAL").param("find", " 심판 ").param("ko", "판결"))
                .andReturn().getResponse().getContentAsString();
        assertThat(padded).isEqualTo(trimmed);
        assertThat(JsonPath.parse(trimmed).<Integer>read("$.count")).isEqualTo(1);
    }

    @Test
    void countWithABlankFindReturns400() throws Exception {
        Long g = gameOfNewProject();
        mvc.perform(get("/api/games/{id}/glossary/apply/count", g)
                        .param("kind", "KO").param("matchMode", "PARTIAL").param("find", "   ").param("ko", "판결"))
                .andExpect(status().isBadRequest());
    }

    // ---- Ruling 28: 사전 확인과 실제 저장 사이의 레이스는 500이 아니라 400으로 답한다 ----
    // 스레드로 진짜 동시 요청을 만들지 않고, ReviewerControllerTest와 같은 방식으로 사전 확인이
    // "겹치는 항목 없음"이라고 잘못 답하게 만들어 실제 유니크 인덱스 위반을 결정적으로 재현한다.

    @Test
    void savingConcurrentlyWithTheSameKeyReturns400NotAServerError() throws Exception {
        Long g = gameOfNewProject();
        String find = "红包충돌" + System.nanoTime();
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                .content(body("SRC", "WHOLE", find, "값1")));

        // 사전 확인(findByGameIdAndKindAndFind)이 "없다"고 잘못 답하게 만든다 — 두 요청이 동시에 같은
        // 항목을 저장하려 할 때, 사전 확인 통과 직후 다른 요청이 먼저 커밋한 것과 같은 상황이다.
        Mockito.when(glossaryRepo.findByGameIdAndKindAndFind(g, Kind.SRC, find)).thenReturn(Optional.empty());

        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                        .content(body("SRC", "WHOLE", find, "값2")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("방금 같은 항목이 저장됐습니다. 목록을 새로 고쳐 주세요."));
    }

    @Test
    void updatingToAKeyThatCollidesDueToAStaleCheckReturns400NotAServerError() throws Exception {
        Long g = gameOfNewProject();
        String findA = "A" + System.nanoTime();
        String findB = "B" + System.nanoTime();
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                .content(body("SRC", "WHOLE", findA, "값A")));
        String savedB = mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                .content(body("SRC", "WHOLE", findB, "값B"))).andReturn().getResponse().getContentAsString();
        Long idB = JsonPath.parse(savedB).read("$.entry.id", Long.class);

        // update()의 사전 확인이 findA가 이미 쓰이고 있다는 걸 놓치게 만든다.
        Mockito.when(glossaryRepo.findByGameIdAndKindAndFind(g, Kind.SRC, findA)).thenReturn(Optional.empty());

        mvc.perform(put("/api/glossary/{id}", idB).contentType(MediaType.APPLICATION_JSON)
                        .content(body("SRC", "WHOLE", findA, "값B2")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("방금 같은 항목이 저장됐습니다. 목록을 새로 고쳐 주세요."));
    }

    @Test
    void sharingConcurrentlyWithTheSameKeyReturns400NotAServerError() throws Exception {
        Long g1 = gameOfNewProject();
        String find = "红包충돌공유" + System.nanoTime();
        String firstSaved = mvc.perform(post("/api/games/{id}/glossary", g1).contentType(MediaType.APPLICATION_JSON)
                .content(body("SRC", "WHOLE", find, "먼저"))).andReturn().getResponse().getContentAsString();
        Long firstId = JsonPath.parse(firstSaved).read("$.entry.id", Long.class);
        mvc.perform(post("/api/glossary/{id}/share", firstId));   // 공유 항목이 이미 생겼다

        Long g2 = gameOfNewProject();
        String secondSaved = mvc.perform(post("/api/games/{id}/glossary", g2).contentType(MediaType.APPLICATION_JSON)
                .content(body("SRC", "WHOLE", find, "나중"))).andReturn().getResponse().getContentAsString();
        Long secondId = JsonPath.parse(secondSaved).read("$.entry.id", Long.class);

        // share()의 사전 확인이 공유 단어집에 이미 같은 키가 있다는 걸 놓치게 만든다.
        Mockito.when(glossaryRepo.findByGameIdIsNullAndKindAndFind(Kind.SRC, find)).thenReturn(Optional.empty());

        mvc.perform(post("/api/glossary/{id}/share", secondId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("방금 같은 항목이 저장됐습니다. 목록을 새로 고쳐 주세요."));
    }

    @Test
    void suggestReturnsTheCurrentRenderingOfTheTerm() throws Exception {
        Long g = gameOfUpgradeProject();
        mvc.perform(get("/api/games/{id}/glossary/suggest", g).param("find", "升星").param("ko", "성급"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows").value(3))
                .andExpect(jsonPath("$.candidates[0].oldKo").value("승급"))
                .andExpect(jsonPath("$.candidates[0].rows").value(3))
                .andExpect(jsonPath("$.candidates[0].example.src").value("升星计划"))
                .andExpect(jsonPath("$.candidates[0].example.before").value("승급 계획"))
                .andExpect(jsonPath("$.candidates[0].example.after").value("성급 계획"));
    }

    @Test
    void suggestWithoutKoLeavesAfterNull() throws Exception {
        Long g = gameOfUpgradeProject();
        mvc.perform(get("/api/games/{id}/glossary/suggest", g).param("find", "升星"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].example.after").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void suggestRejectsShortFindAndUnknownGame() throws Exception {
        Long g = gameOfUpgradeProject();
        mvc.perform(get("/api/games/{id}/glossary/suggest", g).param("find", "升")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/games/{id}/glossary/suggest", g).param("find", " ")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/games/{id}/glossary/suggest", 999999L).param("find", "升星")).andExpect(status().isNotFound());
    }

    @Test
    void recommendListsFrequentSourcePhrasesUntilTheyAreInTheGlossary() throws Exception {
        long pid = TestProjects.uploadRecommend(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));
        Long g = projects.findById(pid).orElseThrow().getGame().getId();
        // 新手礼包1·新手礼包2는 숫자만 달라 한 문장: 礼包은 여섯 행, 다섯 문장
        mvc.perform(get("/api/games/{id}/glossary/recommend", g))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].find").value("礼包"))
                .andExpect(jsonPath("$[0].sentences").value(5))
                .andExpect(jsonPath("$[0].rows").value(6))
                .andExpect(jsonPath("$[0].renderings[0].ko").value("패키지"))
                .andExpect(jsonPath("$[0].renderings[0].rows").value(6))
                .andExpect(jsonPath("$[0].renderings[0].exampleSrc").value("领取礼包"))
                .andExpect(jsonPath("$[0].renderings[0].exampleKo").value("패키지 수령"));
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                .content(body("SRC", "PARTIAL", "礼包", "패키지"))).andExpect(status().isOk());
        mvc.perform(get("/api/games/{id}/glossary/recommend", g))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void recommendRejectsUnknownGame() throws Exception {
        mvc.perform(get("/api/games/{id}/glossary/recommend", 999999L)).andExpect(status().isNotFound());
    }

    @Test
    void savingSrcPartialWithOldKoAppliesAndStoresIt() throws Exception {
        Long g = gameOfUpgradeProject();
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithOldKo("SRC", "PARTIAL", "升星", "성급", "승급")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(3))
                .andExpect(jsonPath("$.entry.oldKo").value("승급"));
        mvc.perform(get("/api/games/{id}/glossary", g).param("scope", "GAME"))
                .andExpect(jsonPath("$[0].oldKo").value("승급"));
    }

    @Test
    void savingSrcPartialWithoutOldKoStillWorksAsWatchOnly() throws Exception {
        Long g = gameOfUpgradeProject();
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                        .content(body("SRC", "PARTIAL", "升星", "성급")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(0))
                .andExpect(jsonPath("$.entry.oldKo").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void oldKoEqualToKoIsRejected() throws Exception {
        Long g = gameOfUpgradeProject();
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithOldKo("SRC", "PARTIAL", "升星", "승급", " 승급 ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("기존 표기와 바꿀 표기가 같습니다."));
    }

    @Test
    void oldKoIsDroppedForNonSrcPartialEntries() throws Exception {
        // Review Focus 4: KO+PARTIAL에 oldKo가 실려 와도 무시하고, 적용은 예전 규칙 그대로다
        Long g = gameOfNewProject();
        mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithOldKo("KO", "PARTIAL", "심판", "판결", "엉뚱")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.entry.oldKo").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void countPreviewHonorsOldKo() throws Exception {
        Long g = gameOfUpgradeProject();
        mvc.perform(get("/api/games/{id}/glossary/apply/count", g)
                        .param("kind", "SRC").param("matchMode", "PARTIAL").param("find", "升星").param("ko", "성급").param("oldKo", "승급"))
                .andExpect(jsonPath("$.count").value(3));
        mvc.perform(get("/api/games/{id}/glossary/apply/count", g)
                        .param("kind", "SRC").param("matchMode", "PARTIAL").param("find", "升星").param("ko", "성급"))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void shareCopiesOldKo() throws Exception {
        Long g = gameOfUpgradeProject();
        String saved = mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithOldKo("SRC", "PARTIAL", "升星", "성급", "승급")))
                .andReturn().getResponse().getContentAsString();
        Long id = JsonPath.parse(saved).read("$.entry.id", Long.class);
        mvc.perform(post("/api/glossary/{id}/share", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shared").value(true))
                .andExpect(jsonPath("$.oldKo").value("승급"));
    }

    @Test
    void updateCanChangeOldKo() throws Exception {
        Long g = gameOfUpgradeProject();
        String saved = mvc.perform(post("/api/games/{id}/glossary", g).contentType(MediaType.APPLICATION_JSON)
                        .content(body("SRC", "PARTIAL", "升星", "성급")))
                .andReturn().getResponse().getContentAsString();
        Long id = JsonPath.parse(saved).read("$.entry.id", Long.class);
        mvc.perform(put("/api/glossary/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithOldKo("SRC", "PARTIAL", "升星", "성급", "승급")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(3))
                .andExpect(jsonPath("$.entry.oldKo").value("승급"));
    }
}
