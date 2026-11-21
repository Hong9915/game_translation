package com.gametrans.normalize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 원문 간체 변환 옵션과 시트 순번. 번체 원문은 옵션을 켠 시트에서만 간체로 저장된다. */
class SimplifiedNormalizeTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired NormalizeConfigRepository configs;

    @Test
    void convertsOnlySourceTextAndKeepsTheSettingWhenOmitted() throws Exception {
        long pid = TestProjects.uploadTraditional(mvc, TestProjects.freshGame());

        normalize(pid, "A", true);
        assertThat(srcTexts(pid, "A")).containsExactly("骑士", "开启");
        assertThat(existingKo(pid, "A")).containsExactly("기사", "열기");

        // 값 없이 다시 정규화하면 저장된 true를 그대로 둔다
        normalize(pid, "A", null);
        assertThat(configs.findByProjectIdAndSheetName(pid, "A").orElseThrow().isSrcToSimplified()).isTrue();
        assertThat(srcTexts(pid, "A")).containsExactly("骑士", "开启");

        // false면 원문 그대로
        normalize(pid, "B", false);
        assertThat(srcTexts(pid, "B")).containsExactly("裡面");
        assertThat(configs.findByProjectIdAndSheetName(pid, "B").orElseThrow().isSrcToSimplified()).isFalse();

        // 새 설정에 값이 없으면 false
        long fresh = TestProjects.uploadTraditional(mvc, TestProjects.freshGame());
        normalize(fresh, "B", null);
        assertThat(configs.findByProjectIdAndSheetName(fresh, "B").orElseThrow().isSrcToSimplified()).isFalse();

        mvc.perform(get("/api/projects/{id}/normalize", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configs[0].config.srcToSimplified").value(true))
                .andExpect(jsonPath("$.configs[1].config.srcToSimplified").value(false));
    }

    @Test
    void recordsTheSheetPositionInTheWorkbook() throws Exception {
        long pid = TestProjects.uploadTraditional(mvc, TestProjects.freshGame());
        normalize(pid, "B", false);
        normalize(pid, "A", false);
        assertThat(configs.findByProjectIdAndSheetName(pid, "A").orElseThrow().getSheetIndex()).isEqualTo(0);
        assertThat(configs.findByProjectIdAndSheetName(pid, "B").orElseThrow().getSheetIndex()).isEqualTo(1);
    }

    private void normalize(long pid, String sheet, Boolean simplified) throws Exception {
        String flag = simplified == null ? "" : ",\"srcToSimplified\":" + simplified;
        String body = "{\"sheetName\":\"" + sheet + "\",\"headerRow\":1,\"pairs\":[{\"srcCol\":\"A\",\"koColMode\":\"EXISTING\",\"koCol\":\"B\"}]" + flag + "}";
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private List<String> srcTexts(long pid, String sheet) {
        return sheetSegments(pid, sheet).stream().map(Segment::getSrcText).toList();
    }

    private List<String> existingKo(long pid, String sheet) {
        return sheetSegments(pid, sheet).stream().map(Segment::getExistingKoText).toList();
    }

    private List<Segment> sheetSegments(long pid, String sheet) {
        return segments.findByProjectIdOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getSheetName().equals(sheet)).toList();
    }
}
