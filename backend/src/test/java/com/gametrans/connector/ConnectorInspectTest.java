package com.gametrans.connector;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/** 커넥터 파일 목록·시트 들여다보기(읽기 전용). */
class ConnectorInspectTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    long upload(String name, byte[] bytes, String game) throws Exception {
        String body = mvc.perform(multipart("/api/projects").file(new MockMultipartFile("file", name, "", bytes)).param("gameName", game))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body).read("$.id", Long.class);
    }

    long gameOf(long pid) { return jdbc.queryForObject("select game_id from project where id = ?", Long.class, pid); }

    @Test
    void filesListsInUploadOrderWithConfiguredCount() throws Exception {
        String game = TestProjects.freshGame();
        long first = TestProjects.uploadTwoSheets(mvc, game);
        long second = TestProjects.uploadExistingKo(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", first).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.sheetNormalize("Items"))).andExpect(status().isOk());
        mvc.perform(get("/api/connector/games/{id}/files", gameOf(first))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].projectId").value((int) first))
                .andExpect(jsonPath("$[0].fileName").value("MultiSheet.xlsx"))
                .andExpect(jsonPath("$[0].configuredSheets").value(1))
                .andExpect(jsonPath("$[0].reviewOnly").value(false))
                .andExpect(jsonPath("$[1].projectId").value((int) second))
                .andExpect(jsonPath("$[1].configuredSheets").value(0));
    }

    @Test
    void inspectShowsSheetsWithoutConfig() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());
        mvc.perform(get("/api/connector/projects/{id}/inspect", pid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value((int) pid))
                .andExpect(jsonPath("$.fileName").value("MultiSheet.xlsx"))
                .andExpect(jsonPath("$.totalSheets").value(5))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.sheets[0].name").value("Items"))
                .andExpect(jsonPath("$.sheets[0].index").value(0))
                .andExpect(jsonPath("$.sheets[0].rowCount").value(4))
                .andExpect(jsonPath("$.sheets[0].top[0][0]").value("ItemID"))
                .andExpect(jsonPath("$.sheets[0].columns[1].letter").value("B"))
                .andExpect(jsonPath("$.sheets[0].columns[1].han").value(75))
                .andExpect(jsonPath("$.sheets[0].guess.src").value("B"))
                .andExpect(jsonPath("$.sheets[0].config").doesNotExist())
                .andExpect(jsonPath("$.sheets[0].canReplace").doesNotExist());
    }

    @Test
    void canReplaceTurnsFalseOnceWorkIsDone() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.sheetNormalize("Items"))).andExpect(status().isOk());
        mvc.perform(get("/api/connector/projects/{id}/inspect", pid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sheets[0].config.sheetName").value("Items"))
                .andExpect(jsonPath("$.sheets[0].canReplace").value(true))
                .andExpect(jsonPath("$.sheets[1].config").doesNotExist());
        jdbc.update("update segment set confirmed = true where id = (select min(id) from segment where project_id = ? and sheet_name = 'Items')", pid);
        mvc.perform(get("/api/connector/projects/{id}/inspect", pid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sheets[0].canReplace").value(false));
    }

    @Test
    void humanRevisionCountsAsWorkButExistingDoesNot() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.sheetNormalize("Items"))).andExpect(status().isOk());
        long sid = jdbc.queryForObject("select min(id) from segment where project_id = ? and sheet_name = 'Items'", Long.class, pid);
        jdbc.update("insert into translation_revision (segment_id, version, text, author, created_at) values (?, 1, '기존', 'EXISTING', now())", sid);
        mvc.perform(get("/api/connector/projects/{id}/inspect", pid)).andExpect(jsonPath("$.sheets[0].canReplace").value(true));
        jdbc.update("insert into translation_revision (segment_id, version, text, author, created_at) values (?, 2, '손봄', 'HUMAN', now())", sid);
        mvc.perform(get("/api/connector/projects/{id}/inspect", pid)).andExpect(jsonPath("$.sheets[0].canReplace").value(false));
    }

    @Test
    void sheetWithRowsButNoConfigHasNullConfigAndNonNullCanReplace() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.sheetNormalize("Items"))).andExpect(status().isOk());
        jdbc.update("delete from normalize_config where project_id = ? and sheet_name = 'Items'", pid);
        mvc.perform(get("/api/connector/projects/{id}/inspect", pid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sheets[0].config").doesNotExist())
                .andExpect(jsonPath("$.sheets[0].canReplace").value(true))
                .andExpect(jsonPath("$.sheets[1].canReplace").doesNotExist());
        jdbc.update("update segment set confirmed = true where id = (select min(id) from segment where project_id = ? and sheet_name = 'Items')", pid);
        mvc.perform(get("/api/connector/projects/{id}/inspect", pid)).andExpect(jsonPath("$.sheets[0].canReplace").value(false));
    }

    @Test
    void offsetAndLimitPageSheets() throws Exception {
        long pid = TestProjects.uploadTwoSheets(mvc, TestProjects.freshGame());
        mvc.perform(get("/api/connector/projects/{id}/inspect?offset=1&limit=1", pid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sheets.length()").value(1))
                .andExpect(jsonPath("$.sheets[0].index").value(1))
                .andExpect(jsonPath("$.offset").value(1));
        mvc.perform(get("/api/connector/projects/{id}/inspect?limit=999", pid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sheets.length()").value(5));
    }

    @Test
    void sheetNameWithSpecialCharactersComesThroughAsIs() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            SheetProbeTest.put(wb.createSheet("대사 （표1） 50%"), 0, 0, "ID");
            wb.write(out);
            bytes = out.toByteArray();
        }
        long pid = upload("Names.xlsx", bytes, TestProjects.freshGame());
        mvc.perform(get("/api/connector/projects/{id}/inspect", pid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sheets[0].name").value("대사 （표1） 50%"));
    }

    @Test
    void csvUploadIsInspectedAsOneSheet() throws Exception {
        byte[] csv = "ID,CN,EN\nA1,骑士,Knight\nA2,龙,Dragon\n".getBytes(StandardCharsets.UTF_8);
        long pid = upload("lines.csv", csv, TestProjects.freshGame());
        mvc.perform(get("/api/connector/projects/{id}/inspect", pid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSheets").value(1))
                .andExpect(jsonPath("$.sheets[0].top[1][1]").value("骑士"))
                .andExpect(jsonPath("$.sheets[0].columns.length()").value(3))
                .andExpect(jsonPath("$.sheets[0].columns[1].han").value(67));
    }

    @Test
    void unknownProjectIs404() throws Exception {
        mvc.perform(get("/api/connector/projects/{id}/inspect", 999999999L)).andExpect(status().isNotFound());
    }
}
