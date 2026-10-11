package com.gametrans.normalize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import java.io.ByteArrayInputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * EXISTING_TO_NEW: 기존 한국어 열(D)에서 읽되, 결과는 맨 끝 새 열에 쓴다.
 * withExistingKo() 워크북(Skill 시트): 2행 한국어 있음, 3행 EN·KR 모두 N/A(의도적 건너뜀),
 * 4행 KR N/A, 5행 빈칸, 6행 원문 none(건너뜀), 7행 KR None → 검수 대상 4행(2·4·5·7).
 */
class ExistingToNewNormalizeTest extends IntegrationTest {
    @Autowired MockMvc mvc;

    private static final String NORMALIZE = """
        {"sheetName":"Skill","headerRow":1,"keyCol":"A",
         "pairs":[{"srcCol":"B","enCol":"C","koColMode":"EXISTING_TO_NEW","koCol":"D","koHeader":"KR_Fix"}]}
        """;

    private long normalized() throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(NORMALIZE))
                .andExpect(status().isOk());
        return pid;
    }

    @Test
    void readsExistingKoreanFromTheNamedColumn() throws Exception {
        long pid = normalized();
        // EXISTING과 같은 읽기 결과: 2행 기존 한국어가 살아 있고, 그 행은 kept로 센다
        mvc.perform(get("/api/projects/{id}/normalize", pid).param("sheet", "Skill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.normalized").value(4))
                .andExpect(jsonPath("$.stats.skipped").value(2))
                .andExpect(jsonPath("$.stats.kept").value(1))
                .andExpect(jsonPath("$.preview[0].existingKoText").value("정의의 심판"));
        // 그 문장이 0차 리비전으로 들어가 Fix의 시작값이 된다
        mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill"))
                .andExpect(jsonPath("$.rows[0].existingKo").value("정의의 심판"))
                .andExpect(jsonPath("$.rows[0].fix").value("정의의 심판"))
                .andExpect(jsonPath("$.rows[0].changed").value(false));
    }

    @Test
    void writesToANewLastColumnLeavingTheOriginalKoreanColumnAlone() throws Exception {
        long pid = normalized();
        String list = mvc.perform(get("/api/review/{id}/rows", pid).param("sheet", "Skill")).andReturn().getResponse().getContentAsString();
        long sid = ((Number) com.jayway.jsonpath.JsonPath.parse(list).read("$.rows[0].id")).longValue();
        mvc.perform(put("/api/segments/{id}/translation", sid).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"정의의 판결\"}"))
                .andExpect(status().isOk());

        byte[] out = mvc.perform(post("/api/projects/{id}/export", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addFlagColumn\":false,\"addHistorySheet\":false,\"addGlossarySheet\":false,\"ratio\":1.0}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out))) {
            Sheet s = wb.getSheet("Skill");
            Row header = s.getRow(0);
            // 원본은 A~D 네 열, 새 열은 E에 붙고 헤더는 koHeader
            assertThat(header.getCell(4).getStringCellValue()).isEqualTo("KR_Fix");
            Row row2 = s.getRow(1);
            assertThat(row2.getCell(3).getStringCellValue()).isEqualTo("정의의 심판");   // D: 원본 그대로
            assertThat(row2.getCell(4).getStringCellValue()).isEqualTo("정의의 판결");   // E: 고친 값
        }
    }

    @Test
    void rejectsMissingKoColumnAndDuplicateNewHeaders() throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content("""
                {"sheetName":"Skill","headerRow":1,"keyCol":"A",
                 "pairs":[{"srcCol":"B","koColMode":"EXISTING_TO_NEW","koHeader":"KR_Fix"}]}
                """))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content("""
                {"sheetName":"Skill","headerRow":1,"keyCol":"A",
                 "pairs":[{"srcCol":"B","koColMode":"EXISTING_TO_NEW","koCol":"D","koHeader":"KR_Fix"},
                          {"srcCol":"C","koColMode":"NEW","koHeader":"KR_Fix"}]}
                """))
                .andExpect(status().isBadRequest());
    }
}
