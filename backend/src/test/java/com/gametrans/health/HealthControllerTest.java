package com.gametrans.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeLlmClient;
import com.gametrans.IntegrationTest;
import com.gametrans.llm.LlmClient;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HealthControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired LlmClient llm;

    // Runs first (see @Order) and dirties the context afterward: HealthController caches its
    // ping result for 10 minutes, so this must be the first request against a fresh context to
    // force a real ping, and must not leave a "false" cache behind for later tests/classes that
    // reuse the same context configuration.
    @Test
    @Order(1)
    @DirtiesContext
    void llmHealthReportsNotOkWhenPingFails() throws Exception {
        FakeLlmClient fake = (FakeLlmClient) llm;
        fake.pingResult = false;
        try {
            mvc.perform(get("/api/health/llm"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.configured").value(true))
                    .andExpect(jsonPath("$.ok").value(false));
        } finally {
            fake.pingResult = true;
        }
    }

    @Test
    @Order(2)
    void llmHealthReportsConfiguredAndOk() throws Exception {
        mvc.perform(get("/api/health/llm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.model").value("gpt-5"));
    }
}
