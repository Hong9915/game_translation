package com.gametrans.connector;

import static com.gametrans.connector.ConnectorNormalizeTest.NAMES;
import static com.gametrans.connector.ConnectorNormalizeTest.pair;
import static com.gametrans.connector.ConnectorNormalizeTest.plan;
import static com.gametrans.connector.ConnectorNormalizeTest.planFull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.memory.MemoryService;
import com.gametrans.normalize.NormalizeConfig;
import com.gametrans.normalize.NormalizeConfigRepository;
import com.gametrans.normalize.NormalizeService;
import com.gametrans.normalize.SegmentRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 커넥터 바꿔치기(replace)가 시트 하나를 한 트랜잭션으로 지키는지: 지운 뒤 실패하면 되돌리고, 지우기 직전에 검수를 다시 본다. */
class ConnectorNormalizeAtomicityTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired NormalizeConfigRepository configs;
    @SpyBean MemoryService memory;
    @SpyBean SegmentRepository segments;
    @SpyBean NormalizeService normalizer;

    long reviewerId;

    @BeforeEach
    void reviewer() throws Exception {
        String body = mvc.perform(post("/api/reviewers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"원자성검수자-" + UUID.randomUUID().toString().substring(0, 8) + "\"}"))
                .andReturn().getResponse().getContentAsString();
        reviewerId = JsonPath.parse(body).read("$.id", Long.class);
    }

    @AfterEach
    void resetSpies() { reset(memory, segments, normalizer); }

    long upload() throws Exception {
        String body = mvc.perform(multipart("/api/projects")
                        .file(new MockMultipartFile("file", "Book.xlsx", "", ConnectorNormalizeTest.book("S", NAMES)))
                        .param("gameName", TestProjects.freshGame()))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body).read("$.id", Long.class);
    }

    ResultActions normalize(long pid, Boolean replace, String... plans) throws Exception {
        return mvc.perform(post("/api/connector/projects/{id}/normalize", pid).header("X-Reviewer-Id", reviewerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sheets\":[" + String.join(",", plans) + "],\"replace\":" + replace + "}"));
    }

    List<Long> ids(long pid) {
        return jdbc.queryForList("select id from segment where project_id = ? and sheet_name = 'S' order by id", Long.class, pid);
    }

    @Test
    void aFailureAfterTheDeleteKeepsTheOldRowsAndConfig() throws Exception {
        long pid = upload();
        normalize(pid, null, plan("S", "B", "C", null)).andExpect(jsonPath("$.results[0].status").value("OK"));
        List<Long> before = ids(pid);
        assertThat(before).hasSize(2);
        clearInvocations(segments, memory);
        // 지우고 새 행을 넣은 뒤(메모리 기록 단계) 터지게 한다
        doThrow(new IllegalStateException("터짐")).when(memory).rememberAll(eq(pid), anyMap(), any(), anyBoolean());

        normalize(pid, true, planFull("S", 1, "A", true, pair("B", null, null))).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("FAILED"))
                .andExpect(jsonPath("$.results[0].reason").value("정규화 중 오류가 났습니다."));

        verify(segments).deleteByProjectIdAndSheetName(pid, "S"); // 지우기까지 갔다
        verify(memory).rememberAll(eq(pid), anyMap(), any(), anyBoolean());
        assertThat(ids(pid)).isEqualTo(before);
        NormalizeConfig c = configs.findByProjectIdAndSheetName(pid, "S").orElseThrow();
        assertThat(c.getKeyCol()).isNull();
        assertThat(c.getPairs().get(0).enCol()).isEqualTo("C");
        assertThat(c.isSrcToSimplified()).isFalse();
    }

    @Test
    void workThatAppearsAfterThePreCheckIsRefusedInsideTheTransaction() throws Exception {
        long pid = upload();
        normalize(pid, null, plan("S", "B", "C", null)).andExpect(jsonPath("$.results[0].status").value("OK"));
        List<Long> before = ids(pid);
        // 앞의 값싼 검사는 검수가 없을 때 지나가고, 정규화가 행을 지우기 직전에 다른 사람이 검수를 남긴 경합.
        // 스파이 답은 정규화 트랜잭션 안에서 돌므로, 남의 검수는 다른 스레드(다른 연결·트랜잭션)에서 커밋한다.
        doAnswer(inv -> {
            Thread other = new Thread(() -> jdbc.update(
                    "insert into translation_revision (segment_id, version, text, author, created_at) values (?, 1, '손봄', 'HUMAN', now())", before.get(0)));
            other.start();
            other.join();
            return inv.callRealMethod();
        }).when(normalizer).normalizeSheet(any(), any(), any(), eq(true));

        normalize(pid, true, planFull("S", 1, "A", true, pair("B", null, null))).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("REFUSED"))
                .andExpect(jsonPath("$.results[0].reason").value(ConnectorNormalizeService.HAS_WORK))
                .andExpect(jsonPath("$.results[0].stats").doesNotExist());

        assertThat(ids(pid)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from translation_revision where segment_id = ? and author = 'HUMAN'", Long.class, before.get(0)))
                .isEqualTo(1);
        NormalizeConfig c = configs.findByProjectIdAndSheetName(pid, "S").orElseThrow();
        assertThat(c.getKeyCol()).isNull();
        assertThat(c.getPairs().get(0).enCol()).isEqualTo("C");
    }

    @Test
    void theInTransactionCheckPassesWhenThereIsNoWork() throws Exception {
        long pid = upload();
        normalize(pid, null, plan("S", "B", "C", null)).andExpect(jsonPath("$.results[0].status").value("OK"));
        List<Long> before = ids(pid);
        clearInvocations(segments);
        normalize(pid, true, plan("S", "B", "C", null)).andExpect(jsonPath("$.results[0].status").value("OK"));
        assertThat(ids(pid)).doesNotContainAnyElementsOf(before);
        verify(segments, times(2)).hasWork(pid, "S"); // 앞의 검사 한 번 + 트랜잭션 안에서 한 번
    }

    @Test
    void theWebPathDoesNotRecheck() throws Exception {
        long pid = upload();
        normalize(pid, null, plan("S", "B", "C", null)).andExpect(jsonPath("$.results[0].status").value("OK"));
        jdbc.update("update segment set confirmed = true where id = ?", ids(pid).get(0));
        clearInvocations(segments);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/projects/{id}/normalize", pid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sheetName\":\"S\",\"headerRow\":1,\"pairs\":[{\"srcCol\":\"B\",\"enCol\":\"C\"}]}"))
                .andExpect(status().isOk());
        verify(segments, never()).hasWork(pid, "S");
        verify(segments, never()).lockSheet(pid, "S");
    }
}
