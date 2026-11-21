package com.gametrans.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** 게임 리뷰 표의 시트 순서는 정규화한 순서가 아니라 워크북 순서를 따른다. */
class SheetOrderTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void reviewRowsFollowWorkbookOrderNotNormalizeOrder() throws Exception {
        long pid = TestProjects.uploadTraditional(mvc, TestProjects.freshGame());
        normalize(pid, "B");
        normalize(pid, "A");
        assertThat(sheetsOfRows(gameId(pid))).containsExactly("A", "A", "B");
    }

    @Test
    void sheetsWithoutIndexKeepIdOrderAndComeAfterIndexedOnes() throws Exception {
        long pid = TestProjects.uploadTraditional(mvc, TestProjects.freshGame());
        normalize(pid, "A");
        normalize(pid, "B");
        // 옛 설정: A는 순번이 없다 → 순번 있는 B 뒤에 온다
        jdbc.update("update normalize_config set sheet_index = null where project_id = ? and sheet_name = 'A'", pid);
        assertThat(sheetsOfRows(gameId(pid))).containsExactly("B", "A", "A");
        // 둘 다 없으면 id 순(A를 먼저 정규화했다)
        jdbc.update("update normalize_config set sheet_index = null where project_id = ?", pid);
        assertThat(sheetsOfRows(gameId(pid))).containsExactly("A", "A", "B");
    }

    private void normalize(long pid, String sheet) throws Exception {
        String body = "{\"sheetName\":\"" + sheet + "\",\"headerRow\":1,\"pairs\":[{\"srcCol\":\"A\",\"koColMode\":\"EXISTING\",\"koCol\":\"B\"}]}";
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private long gameId(long pid) throws Exception {
        String body = mvc.perform(get("/api/projects/{id}", pid)).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.gameId")).longValue();
    }

    private List<String> sheetsOfRows(long gameId) throws Exception {
        String body = mvc.perform(get("/api/games/{g}/review/rows", gameId).param("page", "0").param("size", "50"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.rows[*].sheet");
    }
}
