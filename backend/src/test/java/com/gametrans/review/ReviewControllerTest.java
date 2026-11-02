package com.gametrans.review;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.llm.LlmClient;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class ReviewControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;

    @BeforeEach
    void setUp() {
        FakeLlmClient fake = (FakeLlmClient) llm;
        fake.translateCalls.clear();
        fake.dropOnce.clear();
        fake.breakPlaceholdersOnce.clear();
        fake.translations.clear();
        fake.translations.put("烈焰之剑", "화염의 검");
        fake.translations.put("霜语者", "서리속삭임");
        fake.translations.put("一把附有火焰之力的长剑，攻击时有概率造成灼烧。", "화염의 힘이 깃든 장검. 공격 시 확률적으로 화상을 입힙니다.");
        fake.translations.put("使用后恢复{0}点生命值，冷却{1}秒。", "사용 시 HP {0} 회복. 재사용 대기 {1}초.");
        fake.translations.put("<color=#FFD700>传说</color> 品质装备", "<color=#FFD700>전설</color> 등급 장비");
    }

    private long translated() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        mvc.perform(post("/api/projects/{id}/translate", pid).contentType(MediaType.APPLICATION_JSON).content("{\"basis\":\"CN_ONLY\"}"));
        return pid;
    }

    @Test
    void listsRowsWithLengthFlagsAndStats() throws Exception {
        long pid = translated();
        mvc.perform(get("/api/projects/{id}/segments", pid).param("ratio", "1.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.total").value(5))
                .andExpect(jsonPath("$.stats.filled").value(5))
                .andExpect(jsonPath("$.stats.flagged").value(3))
                .andExpect(jsonPath("$.stats.revised").value(0))
                .andExpect(jsonPath("$.rows[0].indexLabel").value("ItemText!2"))
                .andExpect(jsonPath("$.rows[0].lenSrc").value(4))
                .andExpect(jsonPath("$.rows[0].lenKo").value(4))
                .andExpect(jsonPath("$.rows[0].flagged").value(false))
                .andExpect(jsonPath("$.rows[2].indexLabel").value("ItemText!4"))
                .andExpect(jsonPath("$.rows[2].flagged").value(true))
                .andExpect(jsonPath("$.rows[0].history[0].author").value("LLM"));

        mvc.perform(get("/api/projects/{id}/segments", pid).param("flagged", "true").param("ratio", "1.0"))
                .andExpect(jsonPath("$.rows.length()").value(3));
        mvc.perform(get("/api/projects/{id}/segments", pid).param("ratio", "2.0"))
                .andExpect(jsonPath("$.stats.flagged").value(0));
    }

    @Test
    void humanEditCreatesRevisionAndShortenJobAddsLlmShortenRevision() throws Exception {
        long pid = translated();
        String list = mvc.perform(get("/api/projects/{id}/segments", pid)).andReturn().getResponse().getContentAsString();
        long sid = ((Number) JsonPath.parse(list).read("$.rows[2].id")).longValue();

        mvc.perform(put("/api/segments/{id}/translation", sid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"서리\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.currentText").value("서리"))
                .andExpect(jsonPath("$.row.flagged").value(false))
                .andExpect(jsonPath("$.row.history.length()").value(2))
                .andExpect(jsonPath("$.row.history[1].author").value("HUMAN"));

        mvc.perform(post("/api/projects/{id}/shorten", pid).contentType(MediaType.APPLICATION_JSON).content("{\"ratio\":1.0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));

        mvc.perform(get("/api/projects/{id}/segments", pid).param("ratio", "1.0"))
                .andExpect(jsonPath("$.stats.revised").value(2))
                .andExpect(jsonPath("$.rows[1].history[-1:].author").value("LLM_SHORTEN"));
    }

    @Test
    void resubmittingSameTextDoesNotAddRevision() throws Exception {
        long pid = translated();
        String list = mvc.perform(get("/api/projects/{id}/segments", pid)).andReturn().getResponse().getContentAsString();
        long sid = ((Number) JsonPath.parse(list).read("$.rows[2].id")).longValue();

        mvc.perform(put("/api/segments/{id}/translation", sid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"서리\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.history.length()").value(2));

        mvc.perform(put("/api/segments/{id}/translation", sid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"서리\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.currentText").value("서리"))
                .andExpect(jsonPath("$.row.history.length()").value(2));
    }

    @Test
    void blankTextIsRejected() throws Exception {
        long pid = translated();
        String list = mvc.perform(get("/api/projects/{id}/segments", pid)).andReturn().getResponse().getContentAsString();
        long sid = ((Number) JsonPath.parse(list).read("$.rows[0].id")).longValue();

        mvc.perform(put("/api/segments/{id}/translation", sid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void humanEditThatUnflagsRowIsUntouchedByShorten() throws Exception {
        long pid = translated();
        String list = mvc.perform(get("/api/projects/{id}/segments", pid)).andReturn().getResponse().getContentAsString();
        long sid = ((Number) JsonPath.parse(list).read("$.rows[1].id")).longValue();

        mvc.perform(put("/api/segments/{id}/translation", sid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"화염검\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.flagged").value(false));

        mvc.perform(post("/api/projects/{id}/shorten", pid).contentType(MediaType.APPLICATION_JSON).content("{\"ratio\":1.0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));

        mvc.perform(get("/api/projects/{id}/segments", pid).param("ratio", "1.0"))
                .andExpect(jsonPath("$.rows[1].flagged").value(false))
                .andExpect(jsonPath("$.rows[1].currentText").value("화염검"))
                .andExpect(jsonPath("$.rows[1].history[-1:].author").value("HUMAN"));
    }
}
