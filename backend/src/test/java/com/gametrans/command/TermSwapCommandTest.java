package com.gametrans.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.nullValue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.llm.LlmClient;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 명령으로 고치기의 원문 용어 바꾸기(2026-10-26): 원문에 용어가 든 행을 GPT가 고친 미리 보기 → 확인만 하고 저장. */
class TermSwapCommandTest extends IntegrationTest {
    static final Map<String, Object> TRAP = Map.of("kind", "TERM", "src", "陷阱", "ko", "덫");

    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired LlmClient llmBean;
    @Autowired ObjectMapper json;
    @Autowired TermSwap termSwap;
    @Autowired JdbcTemplate jdbc;

    FakeLlmClient llm;
    long pid;

    @BeforeEach
    void setUp() throws Exception {
        llm = (FakeLlmClient) llmBean;
        llm.termSwap.clear();
        llm.termSwapCalls.clear();
        pid = TestProjects.uploadVariants(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE));
    }

    private Segment row(String src) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getSrcText().equals(src)).findFirst().orElseThrow();
    }

    private ResultActions plan(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/review/{id}/command-fix/plan", pid).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions fix(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/review/{id}/command-fix", pid).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private long gameId() {
        return jdbc.queryForObject("select game_id from project where id = ?", Long.class, pid);
    }

    /** 저장한 행이 있으면 단어장 후보 한 장(원문·바꾼 한국어·행 수·명령 글·COMMAND). 옛 한국어는 없다. */
    @Test
    void savedTermSwapRecordsCandidate() throws Exception {
        Segment a = row("放置陷阱"), c = row("躲避陷阱");
        fix(Map.of("sheet", "Skill", "command", "陷阱 이거를 덫으로", "ops", List.of(TRAP), "items", List.of(
                Map.of("segmentId", a.getId(), "before", "함정을 설치", "after", "덫을 설치"),
                Map.of("segmentId", c.getId(), "before", "함정을 피하기", "after", "덫을 피하기"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(2));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/games/{gameId}/glossary/candidates", gameId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].src").value("陷阱"))
                .andExpect(jsonPath("$[0].ko").value("덫"))
                .andExpect(jsonPath("$[0].oldKo").value(nullValue()))
                .andExpect(jsonPath("$[0].rows").value(2))
                .andExpect(jsonPath("$[0].source").value("COMMAND"));
    }

    /** 저장된 행이 0이면(지금 번역이 미리 본 것과 다름) 후보도 없다. */
    @Test
    void termSwapThatSavesNothingRecordsNoCandidate() throws Exception {
        Segment a = row("放置陷阱");
        fix(Map.of("sheet", "Skill", "command", "陷阱 이거를 덫으로", "ops", List.of(TRAP), "items", List.of(
                Map.of("segmentId", a.getId(), "before", "옛 번역", "after", "덫을 설치"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(0));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/games/{gameId}/glossary/candidates", gameId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** 원문에 陷阱이 든 6행(함정·트랩)만 GPT에 간다. GPT가 그대로 돌려준 행(못 고침)은 미리 보기에서 빠진다. */
    @Test
    void previewRewritesRowsWhoseSourceHasTheTerm() throws Exception {
        llm.termSwap.put("함정을 설치", "덫을 설치");
        llm.termSwap.put("트랩 피해", "덫 피해");
        plan(Map.of("sheet", "Skill", "ops", List.of(TRAP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].before").value("함정을 설치"))
                .andExpect(jsonPath("$.rows[0].after").value("덫을 설치"))
                .andExpect(jsonPath("$.rows[0].by[0]").value("TERM"))
                .andExpect(jsonPath("$.rows[1].after").value("덫 피해"));
        List<String> asked = llm.termSwapCalls.stream().flatMap(List::stream).map(LlmClient.FixTermsRow::ko).toList();
        assertThat(asked).containsExactlyInAnyOrder("함정을 설치", "함정을 해제", "함정을 피하기", "함정을 발견", "트랩 피해", "트랩 강화");
        assertThat(llm.termSwapCalls.get(0).get(0).missing()).isEqualTo(Map.of("陷阱", "덫"));
    }

    @Test
    void termMustStandAloneAndNameBothSides() throws Exception {
        plan(Map.of("sheet", "Skill", "ops", List.of(TRAP, Map.of("kind", "JOSA")))).andExpect(status().isBadRequest());
        plan(Map.of("sheet", "Skill", "ops", List.of(Map.of("kind", "TERM", "src", "陷阱")))).andExpect(status().isBadRequest());
    }

    /** 저장은 다시 묻지 않는다: 지금 번역 그대로·바꿀 한국어가 듦·원문에 용어가 든 행만 쓴다. */
    @Test
    void fixWritesOnlyUnchangedRowsThatNowHaveTheTerm() throws Exception {
        Segment a = row("放置陷阱"), b = row("解除陷阱"), c = row("躲避陷阱"), d = row("每日任务");
        fix(Map.of("sheet", "Skill", "command", "陷阱 이거를 덫으로", "ops", List.of(TRAP), "items", List.of(
                Map.of("segmentId", a.getId(), "before", "함정을 설치", "after", "덫을 설치"),
                Map.of("segmentId", b.getId(), "before", "함정을 해제", "after", "함정 해제"),
                Map.of("segmentId", c.getId(), "before", "옛 번역", "after", "덫을 피하기"),
                Map.of("segmentId", d.getId(), "before", "일일 임무", "after", "덫 임무"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1));
        assertThat(row("放置陷阱").getLatestKo()).isEqualTo("덫을 설치");
        assertThat(row("解除陷阱").getLatestKo()).isEqualTo("함정을 해제");
        assertThat(row("躲避陷阱").getLatestKo()).isEqualTo("함정을 피하기");
        assertThat(row("每日任务").getLatestKo()).isEqualTo("일일 임무");
    }

    /** 숫자만 다른 행은 대표 한 행만 GPT에 묻고 나머지는 숫자를 끼워 만든다. */
    @Test
    void rowsDifferingOnlyInNumbersAskGptOnce() {
        llm.termSwap.put("Level 54·14", "스테이지 54-14");
        List<SheetRow> rows = List.of(
                new SheetRow(-1L, 1, "关卡 54-14", "Level 54·14", false, null, null, false),
                new SheetRow(-2L, 2, "关卡 60-1", "Level 60·1", false, null, null, false),
                new SheetRow(-3L, 3, "关卡 7-3", "Level 7·3", true, null, null, false));
        List<TermSwap.Result> out = termSwap.run(rows, "关卡", "스테이지");
        assertThat(out).extracting(TermSwap.Result::after).containsExactly("스테이지 54-14", "스테이지 60-1");
        assertThat(llm.termSwapCalls).hasSize(1);
        assertThat(llm.termSwapCalls.get(0)).hasSize(1);
    }

    @Test
    void expandFillsNumbersOnlyWhenTheRepresentativeKeptThemInOrder() {
        assertThat(TermSwap.expand("Level 54·14", "스테이지 54-14", "Level 60·1")).isEqualTo("스테이지 60-1");
        assertThat(TermSwap.expand("Level 54·14", "스테이지 14-54", "Level 60·1")).isNull();
        assertThat(TermSwap.expand("Level 54·14", "스테이지 54", "Level 60·1")).isNull();
    }

    /** 원래 글자를 하나도 지우지 않고 용어만 넣은 결과 = 끼워 넣음. 다른 말을 바꾼 결과와 가른다(2026-11-02 任务 → 퀘스트). */
    @Test
    void insertOnlyMeansNothingOfTheOldTextWasRemoved() {
        assertThat(TermSwap.insertOnly("현상금 의뢰 완료 속도 가속 실패", "현상금 의뢰 완료 속도 퀘스트 가속 실패")).isTrue();
        assertThat(TermSwap.insertOnly("누적 충전 이벤트", "누적 충전 이벤트 퀘스트")).isTrue();
        assertThat(TermSwap.insertOnly("마을 재건 퀘스 챕터 25 완료", "마을 재건 퀘스트 챕터 25 완료")).isTrue();
        assertThat(TermSwap.insertOnly("현상금 의뢰 완료 속도 가속 실패", "현상금 퀘스트 완료 속도 가속 실패")).isFalse();
        assertThat(TermSwap.insertOnly("80개의 미션을 완료한", "80개의 퀘스트를 완료한")).isFalse();
        assertThat(TermSwap.insertOnly("Level 54·14", "스테이지 54-14")).isFalse();
    }

    /** 끼워 넣은 행은 미리 보기에 INSERT 경고가 붙는다(화면은 이 행의 체크를 꺼 둔다). 바꾼 행엔 없다. */
    @Test
    void previewWarnsOnRowsWhereTheTermWasOnlyInserted() throws Exception {
        llm.termSwap.put("함정을 설치", "덫을 설치");
        llm.termSwap.put("트랩 피해", "트랩 덫 피해");
        plan(Map.of("sheet", "Skill", "ops", List.of(TRAP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].warnings.length()").value(0))
                .andExpect(jsonPath("$.rows[1].after").value("트랩 덫 피해"))
                .andExpect(jsonPath("$.rows[1].warnings[0].kind").value("INSERT"));
    }
}
