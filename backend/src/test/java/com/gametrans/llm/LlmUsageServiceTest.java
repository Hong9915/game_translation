package com.gametrans.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.job.JobService;
import com.gametrans.job.JobThreadContext;
import com.gametrans.job.TranslationJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class LlmUsageServiceTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired LlmUsageService usage;
    @Autowired JobService jobs;

    @Test
    void parsesPriceSpecLeniently() {
        var p = LlmUsageService.parsePrices("gpt-5=1.25/10, gpt-5-mini=0.25/2, broken, x=1");
        assertThat(p).containsOnlyKeys("gpt-5", "gpt-5-mini");
        assertThat(p.get("gpt-5-mini").outputPerM()).isEqualTo(2.0);
    }

    @Test
    void usageIsAttributedToTheCurrentJobAndSummedPerStageWithCost() throws Exception {
        long pid = TestProjects.upload(mvc);
        TranslationJob job = jobs.create(pid, TranslationJob.Kind.TRANSLATE);
        JobThreadContext.set(job.getId());
        try {
            usage.record(new UsageSink.Usage("TRANSLATE", "gpt-5", 1_000_000, 100_000, 0));
            usage.record(new UsageSink.Usage("TRANSLATE", "gpt-5", 1_000_000, 100_000, 0));
            usage.record(new UsageSink.Usage("EXTRACT_TERMS", "gpt-5-mini", 400_000, 0, 0));
        } finally { JobThreadContext.clear(); }
        jobs.finish(job.getId(), TranslationJob.Status.DONE);

        // gpt-5: 2M 입력 × $1.25 + 0.2M 출력 × $10 = 2.5 + 2 = 4.5 / mini: 0.4M × 0.25 = 0.1  (테스트 프로파일 단가)
        mvc.perform(get("/api/projects/{id}/usage", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calls").value(3))
                .andExpect(jsonPath("$.promptTokens").value(2_400_000))
                .andExpect(jsonPath("$.completionTokens").value(200_000))
                .andExpect(jsonPath("$.priced").value(true))
                .andExpect(jsonPath("$.byStage.length()").value(2))
                .andExpect(jsonPath("$.byStage[?(@.stage=='TRANSLATE')].calls").value(2));
        assertThat(usage.project(pid).costUsd()).isCloseTo(4.6, within(0.0001));
        assertThat(usage.jobSummaryLine(job.getId())).isEqualTo("토큰 사용: 입력 2,400,000 · 출력 200,000");
        mvc.perform(get("/api/usage")).andExpect(jsonPath("$.promptTokens").value(2_400_000));
    }
}
