package com.gametrans.josa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeJosaClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.activity.Activity;
import com.gametrans.activity.ActivityRepository;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 태그 경계 띄어쓰기(2026-10-08): 시트에서 Kiwi가 태그 경계에 공백을 더한 행을 후보로 보이고, 고른 행만 고친다. */
class TagSpacingTest extends IntegrationTest {
    static final String JOINED = "적에게<color=red>함정</color>피해";
    static final String SPACED = "적에게 <color=red>함정</color> 피해";

    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired ActivityRepository activities;
    @Autowired RevisionService revisions;
    @Autowired FakeJosaClient josa;

    long pid;

    @BeforeEach
    void resetJosa() { josa.reset(); }

    long project() throws Exception {
        pid = TestProjects.uploadVariants(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE));
        josa.tagSpaced.put(JOINED, SPACED);
        return pid;
    }

    private ResultActions list(long p) throws Exception {
        return mvc.perform(get("/api/review/{id}/tag-spacing", p).param("sheet", "Skill"));
    }

    private Segment row(String src) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getSrcText().equals(src)).findFirst().orElseThrow();
    }

    private Segment set(String src, String ko) {
        revisions.add(row(src).getId(), ko, TranslationRevision.Author.HUMAN);
        return row(src);
    }

    private ResultActions fix(long p, String json) throws Exception {
        return mvc.perform(post("/api/review/{id}/tag-spacing", p).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String item(Segment s, String before, String after) {
        return "{\"segmentId\":" + s.getId() + ",\"before\":\"" + before + "\",\"after\":\"" + after + "\"}";
    }

    @Test
    void listsOnlyTaggedUnconfirmedRowsKiwiWouldSpace() throws Exception {
        long p = project();
        Segment s = set("陷阱伤害", JOINED);
        set("陷阱强化", "<color=red>함정</color>을 강화");          // Kiwi가 그대로 돌려준다(조사)
        Segment locked = set("放置陷阱", JOINED);
        mvc.perform(put("/api/segments/{id}/marks", locked.getId()).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"));

        list(p)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].segmentId").value(s.getId()))
                .andExpect(jsonPath("$[0].rowNo").value(s.getRowNo()))
                .andExpect(jsonPath("$[0].src").value("陷阱伤害"))
                .andExpect(jsonPath("$[0].before").value(JOINED))
                .andExpect(jsonPath("$[0].after").value(SPACED));
        assertThat(josa.tagSpaceSeen).allMatch(t -> t.contains("<"));   // 태그 없는 행은 보내지 않는다
    }

    @Test
    void listFailsClearlyWhenKiwiIsDown() throws Exception {
        long p = project();
        set("陷阱伤害", JOINED);
        josa.failing = true;
        list(p)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("띄어쓰기 검사기에 연결하지 못했습니다. 잠시 뒤 다시 열어 주세요."));
    }

    @Test
    void fixingRewritesChosenRowsWithOneApplyEntryPerProject() throws Exception {
        long p = project();
        Segment a = set("陷阱伤害", JOINED);
        Segment b = set("陷阱强化", JOINED);
        fix(p, "{\"items\":[" + item(a, JOINED, SPACED) + "," + item(b, JOINED, SPACED) + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(2));
        assertThat(row("陷阱伤害").getLatestKo()).isEqualTo(SPACED);
        assertThat(row("陷阱强化").getLatestKo()).isEqualTo(SPACED);
        assertThat(activities.findByProjectIdOrderByIdDesc(pid)).extracting(Activity::getKind).containsExactly(Activity.Kind.TAG_SPACING);
    }

    @Test
    void rowsChangedSinceListingOrConfirmedAreSkipped() throws Exception {
        long p = project();
        Segment a = set("陷阱伤害", JOINED);
        Segment b = set("放置陷阱", JOINED);
        mvc.perform(put("/api/segments/{id}/marks", b.getId()).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"));
        set("陷阱伤害", "적에게<color=red>함정</color>피해를");          // 목록을 받은 뒤 누가 고쳤다
        fix(p, "{\"items\":[" + item(a, JOINED, SPACED) + "," + item(b, JOINED, SPACED) + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(0));
        assertThat(row("陷阱伤害").getLatestKo()).isEqualTo("적에게<color=red>함정</color>피해를");
        assertThat(row("放置陷阱").getLatestKo()).isEqualTo(JOINED);
    }

    @Test
    void rowsOfAnotherProjectAreSkipped() throws Exception {
        project();
        Segment a = set("陷阱伤害", JOINED);
        long other = project();
        fix(other, "{\"items\":[" + item(a, JOINED, SPACED) + "]}").andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(0));
        assertThat(segments.findById(a.getId()).orElseThrow().getLatestKo()).isEqualTo(JOINED);
    }

    @Test
    void onlyAddedSpacesAreAccepted() throws Exception {
        long p = project();
        Segment a = set("陷阱伤害", JOINED);
        fix(p, "{\"items\":[" + item(a, JOINED, "적에게 <color=red>함정</color> 피해를") + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("띄어쓰기만 다른 꼴만 고칠 수 있습니다: " + JOINED));
        fix(p, "{\"items\":[]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("고칠 행이 없습니다."));
        assertThat(row("陷阱伤害").getLatestKo()).isEqualTo(JOINED);
    }
}
