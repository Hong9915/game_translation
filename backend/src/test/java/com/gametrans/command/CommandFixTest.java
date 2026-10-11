package com.gametrans.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gametrans.FakeJosaClient;
import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.activity.Activity;
import com.gametrans.activity.ActivityRepository;
import com.gametrans.josa.JosaHint;
import com.gametrans.llm.LlmClient;
import com.gametrans.llm.LlmClient.AssistantTurn;
import com.gametrans.llm.LlmClient.ToolCall;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 명령으로 고치기(설계 2026-10-08): 명령·ops·검사 세 모드의 미리 보기, 다시 계산해 맞는 행만 저장. */
class CommandFixTest extends IntegrationTest {
    static final Map<String, Object> REPLACE = Map.of("kind", "REPLACE", "steps", List.of(Map.of("find", "구미호", "replace", "여우", "regex", false)));
    static final Map<String, Object> JOSA = Map.of("kind", "JOSA");

    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired ActivityRepository activities;
    @Autowired RevisionService revisions;
    @Autowired LlmClient llmBean;
    @Autowired FakeJosaClient josa;
    @Autowired ObjectMapper json;

    FakeLlmClient llm;
    long pid;

    @BeforeEach
    void setUp() throws Exception {
        llm = (FakeLlmClient) llmBean;
        llm.toolTurns.clear();
        llm.toolMessages.clear();
        llm.toolSpecsSeen.clear();
        josa.reset();
        pid = TestProjects.uploadVariants(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE));
    }

    private Segment row(String src) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getSrcText().equals(src)).findFirst().orElseThrow();
    }

    private Segment set(String src, String ko) {
        revisions.add(row(src).getId(), ko, TranslationRevision.Author.HUMAN);
        return row(src);
    }

    private void confirm(Segment s) throws Exception {
        mvc.perform(put("/api/segments/{id}/marks", s.getId()).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"));
    }

    private ResultActions plan(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/review/{id}/command-fix/plan", pid).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions fix(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/review/{id}/command-fix", pid).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private static Map<String, Object> item(Segment s, String before, String after) {
        return Map.of("segmentId", s.getId(), "before", before, "after", after);
    }

    @Test
    void opsPreviewChainsReplaceAndJosaWithoutGpt() throws Exception {
        Segment a = set("陷阱伤害", "구미호이 왔다");
        josa.answers.put("여우이 왔다", List.of(new JosaHint(0, "여우이", "여우가", "HANGUL", null)));
        plan(Map.of("sheet", "Skill", "ops", List.of(REPLACE, JOSA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("FIX"))
                .andExpect(jsonPath("$.ops[1].kind").value("JOSA"))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].segmentId").value(a.getId()))
                .andExpect(jsonPath("$.rows[0].after").value("여우가 왔다"))
                .andExpect(jsonPath("$.rows[0].by[0]").value("REPLACE"))
                .andExpect(jsonPath("$.rows[0].by[1]").value("JOSA"));
        assertThat(llm.toolMessages).isEmpty();
    }

    @Test
    void commandRunsTheAgentAndShowsTrailAndWarnings() throws Exception {
        set("陷阱伤害", "구미호이 왔다");
        josa.answers.put("여우이 왔다", List.of(new JosaHint(0, "여우이", "여우가", "HANGUL", null)));
        String replace = json.writeValueAsString(REPLACE);
        llm.toolTurns.add(new AssistantTurn(null, List.of(new ToolCall("c1", "try_ops", "{\"ops\":[" + replace + "]}"))));
        llm.toolTurns.add(new AssistantTurn(null, List.of(new ToolCall("c2", "finish", "{\"summary\":\"구미호를 여우로 바꿉니다.\",\"ops\":[" + replace + "]}"))));
        plan(Map.of("sheet", "Skill", "command", "구미호를 여우로 바꿔줘"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("구미호를 여우로 바꿉니다."))
                .andExpect(jsonPath("$.trail[0].tool").value("try_ops"))
                .andExpect(jsonPath("$.rows[0].after").value("여우이 왔다"))
                .andExpect(jsonPath("$.rows[0].warnings[0].kind").value("JOSA"))
                .andExpect(jsonPath("$.rows[0].warnings[0].text").value("여우이 → 여우가"));
    }

    @Test
    void refusalsAndBadInputsAreBadRequests() throws Exception {
        llm.toolTurns.add(new AssistantTurn("뜻을 봐야 해서 못 합니다.", List.of()));
        plan(Map.of("sheet", "Skill", "command", "어색한 문장 다듬어줘")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("뜻을 봐야 해서 못 합니다."));
        plan(Map.of("sheet", "Skill", "command", " ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("명령을 적어 주세요."));
        plan(Map.of("sheet", "Skill", "ops", List.of(JOSA, Map.of("kind", "UNIFY_SAME_SOURCE")))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("같은 원문 통일은 맨 앞에만 둘 수 있습니다."));
    }

    @Test
    void sidecarDownBlocksJosaOpsButNotReplace() throws Exception {
        set("陷阱伤害", "구미호이 왔다");
        josa.failing = true;
        plan(Map.of("sheet", "Skill", "ops", List.of(JOSA))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(PipelineRunner.SIDECAR_DOWN));
        plan(Map.of("sheet", "Skill", "ops", List.of(REPLACE))).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].after").value("여우이 왔다"))
                .andExpect(jsonPath("$.rows[0].warnings.length()").value(0));
    }

    @Test
    void tagMismatchCheckListsRowsWithTokens() throws Exception {
        Segment a = set("陷阱伤害", "피해 {0}");      // 원문에 없는 자리표시자
        plan(Map.of("sheet", "Skill", "check", "TAG_MISMATCH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("CHECK"))
                .andExpect(jsonPath("$.ops").isEmpty())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].segmentId").value(a.getId()))
                .andExpect(jsonPath("$.rows[0].after").isEmpty())
                .andExpect(jsonPath("$.rows[0].mismatch.ko[0]").value("{0}"));
        assertThat(llm.toolMessages).isEmpty();
    }

    @Test
    void fixSkipsForgedAftersAndRowsChangedOrConfirmedMeanwhile() throws Exception {
        Segment a = set("陷阱伤害", "구미호 등장");
        Segment b = set("陷阱强化", "구미호 강화");
        Segment c = set("放置陷阱", "구미호 설치");
        fix(Map.of("sheet", "Skill", "command", "c", "ops", List.of(REPLACE), "items", List.of(item(a, "구미호 등장", "아무거나"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(0));
        assertThat(row("陷阱伤害").getLatestKo()).isEqualTo("구미호 등장");
        fix(Map.of("sheet", "Skill", "command", "c", "ops", List.of(REPLACE), "items", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("고칠 행이 없습니다."));
        set("陷阱强化", "구미호 강화!");          // 목록을 받은 뒤 누가 고쳤다
        confirm(c);                               // 누가 확정했다
        fix(Map.of("sheet", "Skill", "command", "구미호를 여우로", "ops", List.of(REPLACE), "items", List.of(
                item(a, "구미호 등장", "여우 등장"), item(b, "구미호 강화", "여우 강화"), item(c, "구미호 설치", "여우 설치"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1));
        assertThat(row("陷阱伤害").getLatestKo()).isEqualTo("여우 등장");
        assertThat(row("陷阱强化").getLatestKo()).isEqualTo("구미호 강화!");
        assertThat(row("放置陷阱").getLatestKo()).isEqualTo("구미호 설치");
        List<Activity> made = activities.findByProjectIdOrderByIdDesc(pid);
        assertThat(made).extracting(Activity::getKind).containsExactly(Activity.Kind.COMMAND_FIX);
        assertThat(made.get(0).getDetail()).containsEntry("command", "구미호를 여우로").containsKey("ops");
    }

    @Test
    void fixSavesTheMatchingRowAndSkipsTheForgedOne() throws Exception {
        Segment a = set("陷阱伤害", "구미호 등장");
        Segment b = set("陷阱强化", "구미호 강화");
        fix(Map.of("sheet", "Skill", "command", "c", "ops", List.of(REPLACE), "items", List.of(
                item(a, "구미호 등장", "여우 등장"), item(b, "구미호 강화", "아무거나"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1));
        assertThat(row("陷阱伤害").getLatestKo()).isEqualTo("여우 등장");
        assertThat(row("陷阱强化").getLatestKo()).isEqualTo("구미호 강화");
    }

}
