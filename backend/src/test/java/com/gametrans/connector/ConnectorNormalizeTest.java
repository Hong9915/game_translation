package com.gametrans.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.normalize.NormalizeConfig;
import com.gametrans.normalize.NormalizeConfigRepository;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 커넥터 여러 시트 정규화: 거절·경고 규칙과, 거절·실패·미실행 시트는 DB를 건드리지 않는다는 것. */
class ConnectorNormalizeTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired NormalizeConfigRepository configs;
    @Autowired ConnectorNormalizeService service;

    long reviewerId;

    @BeforeEach
    void reviewer() throws Exception {
        String body = mvc.perform(post("/api/reviewers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"정규화검수자-" + UUID.randomUUID().toString().substring(0, 8) + "\"}"))
                .andReturn().getResponse().getContentAsString();
        reviewerId = JsonPath.parse(body).read("$.id", Long.class);
    }

    @AfterEach
    void restoreClock() { service.clock = System::currentTimeMillis; }

    // ---- 도우미 ----

    static void fill(Sheet s, String[][] rows) {
        for (int r = 0; r < rows.length; r++) {
            Row row = s.createRow(r);
            for (int c = 0; c < rows[r].length; c++) if (rows[r][c] != null && !rows[r][c].isEmpty()) row.createCell(c).setCellValue(rows[r][c]);
        }
    }

    /** 시트 이름, 행들(빈 문자열·null은 빈 칸)을 번갈아 준다. */
    static byte[] book(Object... namesAndRows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < namesAndRows.length; i += 2) fill(wb.createSheet((String) namesAndRows[i]), (String[][]) namesAndRows[i + 1]);
            wb.write(out);
            return out.toByteArray();
        }
    }

    long upload(String name, byte[] bytes) throws Exception {
        String body = mvc.perform(multipart("/api/projects").file(new MockMultipartFile("file", name, "", bytes)).param("gameName", TestProjects.freshGame()))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body).read("$.id", Long.class);
    }

    long uploadBook(Object... namesAndRows) throws Exception { return upload("Book.xlsx", book(namesAndRows)); }

    static String pair(String src, String en, String ko) {
        return "{\"srcCol\":" + q(src) + ",\"enCol\":" + q(en) + ",\"koCol\":" + q(ko) + "}";
    }

    static String q(String s) { return s == null ? "null" : "\"" + s + "\""; }

    /** 쌍 하나짜리 시트 계획(헤더 1행, 키 열 없음). */
    static String plan(String sheet, String src, String en, String ko) { return planFull(sheet, 1, null, false, pair(src, en, ko)); }

    static String planFull(String sheet, Integer headerRow, String keyCol, Boolean simplified, String... pairs) {
        return "{\"sheetName\":" + q(sheet) + ",\"headerRow\":" + headerRow + ",\"keyCol\":" + q(keyCol)
                + ",\"srcToSimplified\":" + simplified + ",\"pairs\":[" + String.join(",", pairs) + "]}";
    }

    ResultActions normalize(long pid, Boolean replace, String... plans) throws Exception {
        return mvc.perform(post("/api/connector/projects/{id}/normalize", pid).header("X-Reviewer-Id", reviewerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sheets\":[" + String.join(",", plans) + "],\"replace\":" + replace + "}"));
    }

    long segmentCount(long pid, String sheet) {
        return jdbc.queryForObject("select count(*) from segment where project_id = ? and sheet_name = ?", Long.class, pid, sheet);
    }

    long configCount(long pid, String sheet) {
        return jdbc.queryForObject("select count(*) from normalize_config where project_id = ? and sheet_name = ?", Long.class, pid, sheet);
    }

    long firstSegment(long pid, String sheet) {
        return jdbc.queryForObject("select min(id) from segment where project_id = ? and sheet_name = ?", Long.class, pid, sheet);
    }

    static final String[][] NAMES = {{"ID", "CN", "EN"}, {"A1", "骑士", "Knight"}, {"A2", "龙", "Dragon"}};

    // ---- 정상 경로 ----

    @Test
    void normalizesSeveralSheetsWithOneCall() throws Exception {
        long pid = uploadBook(
                "P", new String[][]{{"ID", "CN1", "EN1", "KR1", "CN2", "EN2", "KR2"},
                        {"A1", "骑士", "Knight", "기사", "开始", "Start", "시작"}, {"A2", "龙", "Dragon", "용", "结束", "End", "끝"}},
                "Q", NAMES);
        normalize(pid, null,
                planFull("P", 1, "A", false, pair("B", "C", "D"), pair("E", "F", "G")),
                plan("Q", "B", "C", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].sheetName").value("P"))
                .andExpect(jsonPath("$.results[0].status").value("OK"))
                .andExpect(jsonPath("$.results[0].stats.normalized").value(4))
                .andExpect(jsonPath("$.results[1].status").value("OK"))
                .andExpect(jsonPath("$.results[1].stats.normalized").value(2))
                .andExpect(jsonPath("$.results[1].warnings.length()").value(0));

        NormalizeConfig p = configs.findByProjectIdAndSheetName(pid, "P").orElseThrow();
        assertThat(p.getPairs()).extracting(NormalizeConfig.ColumnPair::koColMode)
                .containsExactly(NormalizeConfig.KoColMode.EXISTING_TO_NEW, NormalizeConfig.KoColMode.EXISTING_TO_NEW);
        assertThat(p.getPairs()).extracting(NormalizeConfig.ColumnPair::koHeader).containsExactly("KR_Fix", "KR_Fix2");
        assertThat(p.getPairs()).extracting(NormalizeConfig.ColumnPair::srcCol).containsExactly("B", "E");
        assertThat(p.isRetranslateExisting()).isFalse();
        assertThat(p.isProofreadExisting()).isFalse();
        assertThat(p.getSheetIndex()).isEqualTo(0);
        NormalizeConfig q = configs.findByProjectIdAndSheetName(pid, "Q").orElseThrow();
        assertThat(q.getPairs().get(0).koColMode()).isEqualTo(NormalizeConfig.KoColMode.NEW);
        assertThat(q.getPairs().get(0).koHeader()).isEqualTo("KR_Fix");
        assertThat(q.getSheetIndex()).isEqualTo(1);
    }

    @Test
    void bumpsTheStepOnlyWhenASheetSucceeded() throws Exception {
        long pid = uploadBook("S", NAMES);
        normalize(pid, null, plan("없는", "B", "C", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("FAILED"));
        assertThat(jdbc.queryForObject("select current_step from project where id = ?", Integer.class, pid)).isEqualTo(1);
        normalize(pid, null, plan("S", "B", "C", null)).andExpect(jsonPath("$.results[0].status").value("OK"));
        assertThat(jdbc.queryForObject("select current_step from project where id = ?", Integer.class, pid)).isEqualTo(3);
        jdbc.update("update project set current_step = 1 where id = ?", pid);
        normalize(pid, null, plan("S", "B", "C", null)).andExpect(jsonPath("$.results[0].status").value("REFUSED"));
        assertThat(jdbc.queryForObject("select current_step from project where id = ?", Integer.class, pid)).isEqualTo(1);
    }

    @Test
    void headerRowZeroStartsFromRowOne() throws Exception {
        long pid = uploadBook("S", new String[][]{{"开始", "Start"}, {"结束", "End"}});
        normalize(pid, null, planFull("S", 0, null, false, pair("A", "B", null))).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("OK"))
                .andExpect(jsonPath("$.results[0].stats.normalized").value(2));
        List<String> labels = jdbc.queryForList("select index_label from segment where project_id = ? order by row_no", String.class, pid);
        assertThat(labels).containsExactly("S!1", "S!2");
    }

    @Test
    void headerRowDefaultsToOneWhenOmitted() throws Exception {
        long pid = uploadBook("S", NAMES);
        normalize(pid, null, "{\"sheetName\":\"S\",\"pairs\":[" + pair("B", "C", null) + "]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("OK"))
                .andExpect(jsonPath("$.results[0].stats.normalized").value(2));
        assertThat(configs.findByProjectIdAndSheetName(pid, "S").orElseThrow().getHeaderRow()).isEqualTo(1);
    }

    @Test
    void lowerCaseAndOutOfRangeColumnsDoNotCrash() throws Exception {
        long pid = uploadBook("S", NAMES);
        normalize(pid, null, plan("S", "b", "c", "zz")).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("OK"))
                .andExpect(jsonPath("$.results[0].stats.normalized").value(2));
        NormalizeConfig c = configs.findByProjectIdAndSheetName(pid, "S").orElseThrow();
        assertThat(c.getPairs().get(0).srcCol()).isEqualTo("B");
        assertThat(c.getPairs().get(0).koCol()).isEqualTo("ZZ");
    }

    @Test
    void oddSheetNameWorks() throws Exception {
        long pid = uploadBook("대사 （표1） 50%", NAMES);
        normalize(pid, null, plan("대사 （표1） 50%", "B", "C", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("OK"));
        assertThat(segmentCount(pid, "대사 （표1） 50%")).isEqualTo(2);
    }

    @Test
    void csvFileNormalizes() throws Exception {
        long pid = upload("lines.csv", "ID,CN,EN\nA1,骑士,Knight\nA2,龙,Dragon\n".getBytes(StandardCharsets.UTF_8));
        String sheet = JsonPath.parse(mvc.perform(get("/api/connector/projects/{id}/inspect", pid)).andReturn().getResponse().getContentAsString())
                .read("$.sheets[0].name", String.class);
        normalize(pid, null, plan(sheet, "B", "C", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("OK"))
                .andExpect(jsonPath("$.results[0].stats.normalized").value(2));
    }

    // ---- 거절 ----

    @Test
    void refusesAlreadyNormalized() throws Exception {
        long pid = uploadBook("S", NAMES);
        normalize(pid, null, plan("S", "B", "C", null)).andExpect(jsonPath("$.results[0].status").value("OK"));
        long before = firstSegment(pid, "S");
        normalize(pid, false, plan("S", "B", "C", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("REFUSED"))
                .andExpect(jsonPath("$.results[0].reason").value("이미 정규화한 시트입니다."))
                .andExpect(jsonPath("$.results[0].stats").doesNotExist());
        assertThat(firstSegment(pid, "S")).isEqualTo(before); // 지우고 다시 만들지 않았다
    }

    @Test
    void sameSheetTwiceInOneCallRefusesTheSecond() throws Exception {
        long pid = uploadBook("S", NAMES);
        normalize(pid, null, plan("S", "B", "C", null), plan("S", "B", "C", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("OK"))
                .andExpect(jsonPath("$.results[1].status").value("REFUSED"))
                .andExpect(jsonPath("$.results[1].reason").value("이미 정규화한 시트입니다."));
    }

    @Test
    void replaceAllowedWhenNoWork() throws Exception {
        long pid = uploadBook("S", NAMES);
        normalize(pid, null, plan("S", "B", "C", null));
        long before = firstSegment(pid, "S");
        normalize(pid, true, plan("S", "B", "C", null)).andExpect(jsonPath("$.results[0].status").value("OK"));
        assertThat(firstSegment(pid, "S")).isNotEqualTo(before); // 다시 만들었다
    }

    @Test
    void replaceRefusedWhenWorked() throws Exception {
        long pid = uploadBook("S", NAMES);
        normalize(pid, null, plan("S", "B", "C", null));
        long sid = firstSegment(pid, "S");
        jdbc.update("insert into translation_revision (segment_id, version, text, author, created_at) values (?, 1, '손봄', 'HUMAN', now())", sid);
        normalize(pid, true, plan("S", "B", "C", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("REFUSED"))
                .andExpect(jsonPath("$.results[0].reason").value("번역·검수한 내용이 있어 다시 정규화할 수 없습니다."));
        assertThat(firstSegment(pid, "S")).isEqualTo(sid);
        assertThat(segmentCount(pid, "S")).isEqualTo(2);
    }

    @Test
    void aProofNoteAloneCountsAsWork() throws Exception {
        long pid = uploadBook("S", NAMES);
        normalize(pid, null, plan("S", "B", "C", null));
        jdbc.update("update segment set proof_note = '교정 메모' where id = ?", firstSegment(pid, "S"));
        normalize(pid, true, plan("S", "B", "C", null))
                .andExpect(jsonPath("$.results[0].status").value("REFUSED"))
                .andExpect(jsonPath("$.results[0].reason").value("번역·검수한 내용이 있어 다시 정규화할 수 없습니다."));
        mvc.perform(get("/api/connector/projects/{id}/inspect", pid)).andExpect(jsonPath("$.sheets[0].canReplace").value(false));
    }

    @Test
    void anIgnoredJosaAloneCountsAsWork() throws Exception {
        long pid = uploadBook("S", NAMES);
        normalize(pid, null, plan("S", "B", "C", null));
        jdbc.update("update segment set josa_ignored = true where id = ?", firstSegment(pid, "S"));
        normalize(pid, true, plan("S", "B", "C", null))
                .andExpect(jsonPath("$.results[0].status").value("REFUSED"))
                .andExpect(jsonPath("$.results[0].reason").value("번역·검수한 내용이 있어 다시 정규화할 수 없습니다."));
        mvc.perform(get("/api/connector/projects/{id}/inspect", pid)).andExpect(jsonPath("$.sheets[0].canReplace").value(false));
    }

    @Test
    void refusesSameSrcAndKo() throws Exception {
        long pid = uploadBook("S", NAMES, "T", NAMES);
        normalize(pid, null, plan("S", "B", "C", "b"),
                planFull("T", 1, null, false, pair("B", "C", "D"), pair("D", null, "B")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("REFUSED"))
                .andExpect(jsonPath("$.results[0].reason").value("원문 열과 한국어 열이 같습니다(B)."))
                .andExpect(jsonPath("$.results[1].status").value("REFUSED"))
                .andExpect(jsonPath("$.results[1].reason").value("원문 열과 한국어 열이 같습니다(D)."));
        assertThat(segmentCount(pid, "S") + segmentCount(pid, "T") + configCount(pid, "S") + configCount(pid, "T")).isZero();
    }

    @Test
    void refusesKoColumnWithoutHangul() throws Exception {
        String[][] rows = {{"ID", "CN", "CN2"}, {"1", "骑士", "骑士"}, {"2", "龙", "龙"}, {"3", "剑", "剑"}, {"4", "盾", "盾"}, {"5", "弓", "弓"}};
        long pid = uploadBook("S", rows);
        normalize(pid, null, plan("S", "B", null, "C")).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("REFUSED"))
                .andExpect(jsonPath("$.results[0].reason").value("한국어 열(C)에 한글이 거의 없습니다(0%)."));
        assertThat(segmentCount(pid, "S") + configCount(pid, "S")).isZero();
    }

    @Test
    void koColumnWithFewFilledCellsIsNotRefused() throws Exception {
        String[][] rows = {{"ID", "CN", "CN2"}, {"1", "骑士", "骑士"}, {"2", "龙", "龙"}, {"3", "剑", "剑"}};
        long pid = uploadBook("S", rows);
        normalize(pid, null, plan("S", "B", null, "C")).andExpect(jsonPath("$.results[0].status").value("OK"));
    }

    // ---- 실패 ----

    @Test
    void oneFailureDoesNotStopOthers() throws Exception {
        long pid = uploadBook("S", NAMES);
        normalize(pid, null, plan("없는 시트", "B", "C", null), plan("S", "B", "C", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("FAILED"))
                .andExpect(jsonPath("$.results[0].reason").value("시트를 찾을 수 없습니다: 없는 시트"))
                .andExpect(jsonPath("$.results[0].stats").doesNotExist())
                .andExpect(jsonPath("$.results[1].status").value("OK"));
        assertThat(segmentCount(pid, "S")).isEqualTo(2);
        assertThat(configCount(pid, "없는 시트")).isZero();
    }

    @Test
    void aThrownValidationErrorFailsOnlyThatSheetAndKeepsItsMessage() throws Exception {
        long pid = uploadBook("S", NAMES, "T", NAMES);
        // 원문 열이 빈 쌍은 NormalizeService가 거부한다
        normalize(pid, null, plan("S", "B", "C", null),
                planFull("T", 1, null, false, pair("B", null, null), pair("", null, null)))
                .andExpect(jsonPath("$.results[0].status").value("OK"))
                .andExpect(jsonPath("$.results[1].status").value("FAILED"))
                .andExpect(jsonPath("$.results[1].reason").value("간체 원문 열을 지정하세요."));
        assertThat(segmentCount(pid, "S")).isEqualTo(2); // 앞 시트는 롤백되지 않았다
        assertThat(segmentCount(pid, "T")).isZero();
    }

    // ---- 시간 ----

    @Test
    void sheetsAfterTheTimeBudgetAreNotRun() throws Exception {
        // 가장 긴 호출은 이 시간 + 시트 하나 — 프로드가 맥보다 느려 30초로 둔다(시트 수 상한 40과 다르다)
        assertThat(ConnectorNormalizeService.TIME_BUDGET_MS).isEqualTo(30_000L);
        assertThat(ConnectorNormalizeService.MAX_SHEETS).isEqualTo(40);
        long pid = uploadBook("S1", NAMES, "S2", NAMES, "S3", NAMES);
        AtomicInteger calls = new AtomicInteger();
        service.clock = () -> calls.getAndIncrement() == 0 ? 0L : ConnectorNormalizeService.TIME_BUDGET_MS;
        normalize(pid, null, plan("S1", "B", "C", null), plan("S2", "B", "C", null), plan("S3", "B", "C", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("OK"))
                .andExpect(jsonPath("$.results[1].status").value("NOT_RUN"))
                .andExpect(jsonPath("$.results[1].reason").value("시간이 다 돼 하지 않았습니다. 이 시트부터 다시 부르세요."))
                .andExpect(jsonPath("$.results[1].stats").doesNotExist())
                .andExpect(jsonPath("$.results[1].warnings.length()").value(0))
                .andExpect(jsonPath("$.results[2].status").value("NOT_RUN"));
        assertThat(segmentCount(pid, "S2") + segmentCount(pid, "S3") + configCount(pid, "S2") + configCount(pid, "S3")).isZero();
        assertThat(segmentCount(pid, "S1")).isEqualTo(2);
    }

    // ---- 경고 ----

    @Test
    void warnsAboutAFallbackColumn() throws Exception {
        String[][] rows = {{"简体", "繁体", "韩语"}, {"", "騎士", "기사"}, {"龙", "龍", "용"}, {"", "劍", "검"}};
        long pid = uploadBook("S", rows);
        normalize(pid, null, plan("S", "A", null, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("OK"))
                .andExpect(jsonPath("$.results[0].stats.normalized").value(1))
                .andExpect(jsonPath("$.results[0].warnings[?(@ == '원문이 빈 2행은 B열에 글이 있습니다.')]").isNotEmpty());
        normalize(pid, true, planFull("S", 1, null, true, pair("B", null, null))).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("OK"))
                .andExpect(jsonPath("$.results[0].stats.normalized").value(3));
        assertThat(jdbc.queryForList("select src_text from segment where project_id = ? order by row_no", String.class, pid))
                .containsExactly("骑士", "龙", "剑");
    }

    @Test
    void fallbackWarningCountsRowsBeyondTheSample() throws Exception {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"简体", "繁体"});
        for (int i = 0; i < 305; i++) rows.add(new String[]{"", "騎士"});
        long pid = uploadBook("S", rows.toArray(new String[0][]));
        normalize(pid, null, plan("S", "A", null, null))
                .andExpect(jsonPath("$.results[0].warnings[?(@ == '원문이 빈 305행은 B열에 글이 있습니다.')]").isNotEmpty());
    }

    @Test
    void columnsWithARoleAreNotReportedAsFallback() throws Exception {
        String[][] rows = {{"简体", "繁体", "EN", "KR"}, {"", "騎士", "Knight", "기사"}, {"龙", "龍", "Dragon", "용"}};
        long pid = uploadBook("S", rows);
        // B는 en 열이라 역할이 있다
        normalize(pid, null, plan("S", "A", "B", "D")).andExpect(jsonPath("$.results[0].status").value("OK"))
                .andExpect(jsonPath("$.results[0].warnings[?(@ =~ /원문이 빈.*/)]").isEmpty());
    }

    @Test
    void warnsTraditionalWithoutSimplify() throws Exception {
        long pid = uploadBook("S", new String[][]{{"騎士", "x"}, {"龍", "y"}});
        normalize(pid, null, planFull("S", 0, null, false, pair("A", null, null))).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("OK"))
                .andExpect(jsonPath("$.results[0].warnings[0]").value("원문에 번체가 섞였는데 간체 변환이 꺼져 있습니다(100%)."));
        long pid2 = uploadBook("S", new String[][]{{"騎士", "x"}, {"龍", "y"}});
        normalize(pid2, null, planFull("S", 0, null, true, pair("A", null, null)))
                .andExpect(jsonPath("$.results[0].warnings.length()").value(0));
    }

    @Test
    void warnsWhenTheSourceColumnHasFewHan() throws Exception {
        long pid = uploadBook("S", new String[][]{{"Knight", "x"}, {"Dragon", "y"}});
        normalize(pid, null, planFull("S", 0, null, false, pair("A", null, null))).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("OK"))
                .andExpect(jsonPath("$.results[0].warnings[0]").value("원문 열(A)에 한자가 적습니다(0%)."));
    }

    @Test
    void warnsWhenMoreThanHalfAreSkipped() throws Exception {
        long pid = uploadBook("S", new String[][]{{"骑士", "x"}, {"", "x"}, {"", "x"}});
        normalize(pid, null, planFull("S", 0, null, false, pair("A", null, null))).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("OK"))
                .andExpect(jsonPath("$.results[0].warnings[0]").value("건너뛴 행이 절반을 넘습니다(2/3)."));
    }

    @Test
    void warnsWhenNoRowsWereNormalized() throws Exception {
        long pid = uploadBook("S", new String[][]{{"ID", "CN"}});
        normalize(pid, null, plan("S", "B", null, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("OK"))
                .andExpect(jsonPath("$.results[0].stats.normalized").value(0))
                .andExpect(jsonPath("$.results[0].warnings[?(@ == '정규화된 행이 없습니다.')]").isNotEmpty());
    }

    // ---- 요청 검사 ----

    @Test
    void rejectsMoreThanTheMaximumNumberOfSheets() throws Exception {
        long pid = uploadBook("S", NAMES);
        String[] plans = IntStream.range(0, ConnectorNormalizeService.MAX_SHEETS + 1).mapToObj(i -> plan("S" + i, "B", "C", null)).toArray(String[]::new);
        normalize(pid, null, plans).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("한 번에 1~40개 시트를 정규화할 수 있습니다."));
        normalize(pid, null).andExpect(status().isBadRequest());
    }

    @Test
    void missingOrUnknownReviewerIs400() throws Exception {
        long pid = uploadBook("S", NAMES);
        String body = "{\"sheets\":[" + plan("S", "B", "C", null) + "]}";
        mvc.perform(post("/api/connector/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/connector/projects/{id}/normalize", pid).header("X-Reviewer-Id", 999999999L)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        assertThat(segmentCount(pid, "S")).isZero();
    }

    @Test
    void webNormalizeStillAllowsRerunOnWorkedSheet() throws Exception {
        long pid = uploadBook("S", NAMES);
        normalize(pid, null, plan("S", "B", "C", null));
        jdbc.update("update segment set confirmed = true where id = ?", firstSegment(pid, "S"));
        normalize(pid, true, plan("S", "B", "C", null)).andExpect(jsonPath("$.results[0].status").value("REFUSED"));
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sheetName\":\"S\",\"headerRow\":1,\"pairs\":[{\"srcCol\":\"B\",\"enCol\":\"C\"}]}"))
                .andExpect(status().isOk());
        assertThat(segmentCount(pid, "S")).isEqualTo(2);
    }

    @Test
    void segmentsWithoutConfigCountAsAlreadyNormalized() throws Exception {
        long pid = uploadBook("S", NAMES);
        normalize(pid, null, plan("S", "B", "C", null));
        jdbc.update("delete from normalize_config where project_id = ? and sheet_name = 'S'", pid);
        long sid = firstSegment(pid, "S");
        normalize(pid, false, plan("S", "B", "C", null))
                .andExpect(jsonPath("$.results[0].status").value("REFUSED"))
                .andExpect(jsonPath("$.results[0].reason").value("이미 정규화한 시트입니다."));
        jdbc.update("update segment set confirmed = true where id = ?", sid);
        normalize(pid, true, plan("S", "B", "C", null))
                .andExpect(jsonPath("$.results[0].status").value("REFUSED"))
                .andExpect(jsonPath("$.results[0].reason").value("번역·검수한 내용이 있어 다시 정규화할 수 없습니다."));
        assertThat(firstSegment(pid, "S")).isEqualTo(sid);
        jdbc.update("update segment set confirmed = false where id = ?", sid);
        normalize(pid, true, plan("S", "B", "C", null)).andExpect(jsonPath("$.results[0].status").value("OK"));
    }

    @Test
    void emptySheetEntriesAreRejectedUpFront() throws Exception {
        long pid = uploadBook("S", NAMES);
        String pairs = "\"pairs\":[" + pair("B", "C", null) + "]";
        for (String bad : new String[]{"null", "{\"sheetName\":null," + pairs + "}", "{\"sheetName\":\" \"," + pairs + "}",
                "{\"sheetName\":\"S\",\"pairs\":null}", "{\"sheetName\":\"S\",\"pairs\":[]}"}) {
            normalize(pid, null, plan("S", "B", "C", null), bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("시트 정보가 비어 있습니다."));
        }
        assertThat(segmentCount(pid, "S")).isZero(); // 아무것도 돌리지 않았다
    }

    @Test
    void aNegativeHeaderRowIsRejectedUpFront() throws Exception {
        long pid = uploadBook("S", NAMES, "T", NAMES);
        normalize(pid, null, plan("S", "B", "C", null), planFull("T", -1, null, false, pair("B", "C", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("헤더 행은 0(없음) 이상이어야 합니다."));
        assertThat(segmentCount(pid, "S") + segmentCount(pid, "T") + configCount(pid, "S") + configCount(pid, "T")).isZero();
    }

    @Test
    void aValueThatIsNotAColumnLetterFailsThatSheet() throws Exception {
        long pid = uploadBook("S", NAMES, "T", NAMES, "U", NAMES, "V", NAMES, "W", NAMES);
        normalize(pid, null,
                plan("S", "a1", "C", null),
                planFull("T", 1, "1", false, pair("B", "C", null)),
                plan("U", "B", "C D", null),
                plan("V", "B", "C", "ABCD"),
                plan("W", "b", "c", "zz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("FAILED"))
                .andExpect(jsonPath("$.results[0].reason").value("열 문자가 아닙니다: A1"))
                .andExpect(jsonPath("$.results[1].status").value("FAILED"))
                .andExpect(jsonPath("$.results[1].reason").value("열 문자가 아닙니다: 1"))
                .andExpect(jsonPath("$.results[2].status").value("FAILED"))
                .andExpect(jsonPath("$.results[2].reason").value("열 문자가 아닙니다: C D"))
                .andExpect(jsonPath("$.results[3].status").value("FAILED"))
                .andExpect(jsonPath("$.results[3].reason").value("열 문자가 아닙니다: ABCD"))
                .andExpect(jsonPath("$.results[4].status").value("OK")); // 소문자·시트 밖 열은 지금처럼 된다
        for (String s : new String[]{"S", "T", "U", "V"}) assertThat(segmentCount(pid, s) + configCount(pid, s)).isZero();
        assertThat(segmentCount(pid, "W")).isEqualTo(2);
    }

    @Test
    void workbookOpensWaitTheirTurn() throws Exception {
        long pid = uploadBook("S", NAMES);
        service.workbookGate.acquire(); // 다른 호출이 워크북을 열고 있는 셈
        CompletableFuture<ConnectorDtos.InspectResponse> inspect = CompletableFuture.supplyAsync(() -> service.inspect(pid, null, null));
        try {
            Thread.sleep(300);
            assertThat(inspect).isNotDone();
        } finally {
            service.workbookGate.release();
        }
        assertThat(inspect.get(30, TimeUnit.SECONDS).sheets()).hasSize(1);
        // 실패한 호출도 문을 돌려준다
        normalize(pid, null, plan("없는", "B", "C", null)).andExpect(jsonPath("$.results[0].status").value("FAILED"));
        assertThat(service.workbookGate.availablePermits()).isEqualTo(1);
    }

    @Test
    void resultsComeBackInRequestOrder() throws Exception {
        long pid = uploadBook("A", NAMES, "B", NAMES);
        normalize(pid, null, plan("B", "B", "C", null), plan("A", "B", "C", null))
                .andExpect(jsonPath("$.results[0].sheetName").value("B"))
                .andExpect(jsonPath("$.results[1].sheetName").value("A"));
    }
}
