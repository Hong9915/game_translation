package com.gametrans.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.llm.LlmClient;
import java.io.ByteArrayInputStream;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 설정한 시트를 모두 채워 파일 하나로 낸다. twoSheets()의 Items·Skills는 행 번호가 겹쳐서
 * (시트, 행)이 아니라 행 번호만으로 찾으면 서로의 번역이 들어가는 것이 바로 드러난다.
 */
class MultiSheetExportTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;

    @BeforeEach
    void setUp() {
        FakeLlmClient fake = (FakeLlmClient) llm;
        fake.dropOnce.clear();
        fake.breakPlaceholdersOnce.clear();
        fake.translations.clear();
        fake.translations.put("烈焰之剑", "화염검");       // Items!2
        fake.translations.put("霜语者", "서리");           // Items!3 과 Skills!3(같은 원문)
        fake.translations.put("破晓之盾", "새벽의 방패");   // Items!4
        fake.translations.put("正义裁决", "정의 판결");     // Skills!2
        fake.translations.put("冒险开始", "모험이 시작된다");  // Quest!2 설명 — 원문 4자, 번역 7자라 ratio 1.0에서 플래그
        fake.translations.put("限时特惠", "한정 특가");     // Quest!3 설명
    }

    @Test
    void exportFillsEverySheetAndOverlappingRowNumbersDoNotSwap() throws Exception {
        long pid = twoSheetProject();

        byte[] body = mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addFlagColumn\":false,\"addHistorySheet\":true,\"addGlossarySheet\":false,\"ratio\":2.0}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            Sheet items = wb.getSheet("Items");
            assertThat(items.getRow(0).getCell(3).getStringCellValue()).isEqualTo("KO");
            assertThat(items.getRow(1).getCell(3).getStringCellValue()).isEqualTo("화염검");
            assertThat(items.getRow(2).getCell(3).getStringCellValue()).isEqualTo("서리");
            assertThat(items.getRow(3).getCell(3).getStringCellValue()).isEqualTo("새벽의 방패");

            // 두 시트의 2행은 행 번호가 같다 — 서로의 번역이 들어가면 여기서 드러난다
            Sheet skills = wb.getSheet("Skills");
            assertThat(skills.getRow(0).getCell(3).getStringCellValue()).isEqualTo("KO");
            assertThat(skills.getRow(1).getCell(3).getStringCellValue()).isEqualTo("정의 판결");
            assertThat(skills.getRow(2).getCell(3).getStringCellValue()).isEqualTo("서리");

            // 설정하지 않은 시트는 그대로 — 원문 열만 있고 한국어 열이 붙지 않았다
            Sheet other = wb.getSheet("기타 시트");
            assertThat(other.getRow(1).getCell(1).getStringCellValue()).isEqualTo("冒险开始");
            assertThat(other.getRow(0).getCell(3)).isNull();
            assertThat(other.getRow(1).getCell(3)).isNull();

            // 이력 시트는 하나. 인덱스에 시트 이름이 들어 있어 두 시트의 2행이 구분된다.
            Sheet history = wb.getSheet("수정 이력");
            assertThat(history).isNotNull();
            java.util.List<String> labels = new java.util.ArrayList<>();
            for (int i = 1; i <= history.getLastRowNum(); i++) labels.add(history.getRow(i).getCell(0).getStringCellValue());
            assertThat(labels).contains("Items!2", "Skills!2");
        }
    }

    /**
     * 시트마다 열 배치가 다르다: Items·Skills는 3열에 쌍 하나(새 열 D, 플래그 E), Quest는 6열에 쌍 둘
     * (기존 D열 + 새 열 G, 플래그 H). 한국어 열·플래그 열은 시트별 설정으로 따로 계산해야 한다 —
     * 한 시트 것을 한 번 계산해 돌려 쓰면 Quest의 한국어가 E·F(원문 설명 열)를 덮어쓰며 바로 드러난다.
     */
    @Test
    void eachSheetGetsItsOwnKoAndFlagColumnPositions() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());
        normalize(pid, "Items");
        normalize(pid, "Skills");
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                        .content(TestProjects.QUEST_NORMALIZE))
                .andExpect(status().isOk());
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"))
                .andExpect(status().isOk());

        byte[] body = mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addFlagColumn\":true,\"addHistorySheet\":false,\"addGlossarySheet\":false,\"ratio\":1.0}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            // 좁은 시트: 한국어는 D, 플래그는 E
            Sheet items = wb.getSheet("Items");
            assertThat(cells(items, 0)).containsExactly("ItemID", "CN_Text", "EN_Text", "KO", "UI_FLAG");
            assertThat(cells(items, 1)).containsExactly("IT_1", "烈焰之剑", "Blazing Sword", "화염검");   // 플래그 없는 행은 E를 만들지 않는다
            assertThat(cells(items, 3)).containsExactly("IT_3", "破晓之盾", "Dawnbreak Shield", "새벽의 방패", "UI 깨짐 주의");
            assertThat(cells(wb.getSheet("Skills"), 0)).containsExactly("SkillID", "CN_Text", "EN_Text", "KO", "UI_FLAG");

            // 넓은 시트: 이름은 기존 D열(헤더 그대로), 설명은 새 G열, 플래그는 그 뒤 H열.
            // E·F(원문 설명·영어)는 그대로 남아 있어야 한다 — 좁은 시트의 D·E 자리를 돌려 썼다면 여기가 덮인다.
            Sheet quest = wb.getSheet("Quest");
            assertThat(cells(quest, 0)).containsExactly("QuestID", "CN_Name", "EN_Name", "KR_Name", "CN_Desc", "EN_Desc", "KO_Desc", "UI_FLAG");
            assertThat(cells(quest, 1)).containsExactly("QT_1", "霜语者", "Frostwhisper", "서리", "冒险开始", "The adventure begins", "모험이 시작된다", "UI 깨짐 주의");
            assertThat(cells(quest, 2)).containsExactly("QT_2", "破晓之盾", "Dawnbreak Shield", "새벽의 방패", "限时特惠", "Limited-time deal", "한정 특가", "UI 깨짐 주의");
        }
    }

    /** 미리보기는 ?sheet=로 고르고(기본은 첫 설정 시트), 집계는 내보낼 파일 전체 기준이다. */
    @Test
    void previewPicksTheSheetAndListsTheConfiguredOnes() throws Exception {
        long pid = twoSheetProject();

        mvc.perform(get("/api/projects/{id}/export/preview", pid).param("ratio", "2.0").param("sheet", "Skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sheetName").value("Skills"))
                .andExpect(jsonPath("$.sheets.length()").value(2))
                .andExpect(jsonPath("$.sheets[0]").value("Items"))
                .andExpect(jsonPath("$.sheets[1]").value("Skills"))
                .andExpect(jsonPath("$.otherSheetCount").value(sheetCount(pid) - 2))  // 그대로 두는 시트 수
                .andExpect(jsonPath("$.filledRows").value(5))        // 집계는 프로젝트 전체
                .andExpect(jsonPath("$.totalRows").value(5))
                .andExpect(jsonPath("$.rows.length()").value(2))     // 표는 고른 시트만
                .andExpect(jsonPath("$.rows[0][0]").value("SK_1"))
                .andExpect(jsonPath("$.rows[0][3]").value("정의 판결"))
                .andExpect(jsonPath("$.rows[1][3]").value("서리"));

        // sheet를 빼면 첫 설정 시트
        mvc.perform(get("/api/projects/{id}/export/preview", pid).param("ratio", "2.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sheetName").value("Items"))
                .andExpect(jsonPath("$.rows.length()").value(3))
                .andExpect(jsonPath("$.rows[0][3]").value("화염검"));

        // 설정에 없는 시트는 400 — 워크북에 있는 시트여도 마찬가지다
        mvc.perform(get("/api/projects/{id}/export/preview", pid).param("sheet", "기타 시트"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/projects/{id}/export/preview", pid).param("sheet", "없는시트"))
                .andExpect(status().isBadRequest());
    }

    /** 워크북의 시트를 모두 설정하면 "그대로 두는 시트"는 없다. */
    @Test
    void otherSheetCountIsZeroWhenEverySheetIsConfigured() throws Exception {
        long pid = twoSheetProject();
        for (String sheet : sheetNames(pid)) normalize(pid, sheet);

        mvc.perform(get("/api/projects/{id}/export/preview", pid).param("ratio", "2.0"))
                .andExpect(jsonPath("$.sheets.length()").value(sheetCount(pid)))
                .andExpect(jsonPath("$.otherSheetCount").value(0));
    }

    /** 시트를 하나만 설정했으면 나머지 시트의 셀 값은 손대지 않는다(예전 동작 그대로). */
    @Test
    void oneConfiguredSheetLeavesTheOtherSheetsCellsUntouched() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());
        normalize(pid, "Items");
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"))
                .andExpect(status().isOk());

        byte[] body = mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addFlagColumn\":true,\"addHistorySheet\":false,\"addGlossarySheet\":false,\"ratio\":2.0}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            assertThat(wb.getSheet("Items").getRow(1).getCell(3).getStringCellValue()).isEqualTo("화염검");

            Sheet skills = wb.getSheet("Skills");
            assertThat(cells(skills, 0)).containsExactly("SkillID", "CN_Text", "EN_Text");
            assertThat(cells(skills, 1)).containsExactly("SK_1", "正义裁决", "Rule of Law");
            assertThat(cells(skills, 2)).containsExactly("SK_2", "霜语者", "Frostwhisper");
        }
    }

    /** 워크북의 시트 이름과 수 — twoSheets()에 시트가 늘어도 "그대로 두는 시트" 셈이 흔들리지 않게 여기서 읽는다. */
    private java.util.List<String> sheetNames(long pid) throws Exception {
        String body = mvc.perform(get("/api/projects/{id}/sheets", pid)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.sheets[*].name");
    }

    private int sheetCount(long pid) throws Exception { return sheetNames(pid).size(); }

    private static java.util.List<String> cells(Sheet sheet, int rowIdx) {
        java.util.List<String> out = new java.util.ArrayList<>();
        org.apache.poi.ss.usermodel.Row row = sheet.getRow(rowIdx);
        for (int c = 0; c < row.getLastCellNum(); c++) {
            org.apache.poi.ss.usermodel.Cell cell = row.getCell(c);
            out.add(cell == null ? "" : com.gametrans.xlsx.WorkbookInspector.cellText(cell));
        }
        return out;
    }

    private long twoSheetProject() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());
        normalize(pid, "Items");
        normalize(pid, "Skills");
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"))
                .andExpect(status().isOk());
        return pid;
    }

    private void normalize(long pid, String sheet) throws Exception {
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                        .content(TestProjects.sheetNormalize(sheet)))
                .andExpect(status().isOk());
    }
}
