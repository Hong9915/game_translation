package com.gametrans.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

// Overrides app.openai.api-key to blank; this gives the test its own Spring context (distinct
// property set), so it doesn't share HealthController's cached ping state with HealthControllerTest.
@SpringBootTest(properties = "app.openai.api-key=")
class HealthControllerNotConfiguredTest extends IntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void llmHealthReportsNotConfiguredWhenApiKeyBlank() throws Exception {
        mvc.perform(get("/api/health/llm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.ok").value(false));
    }
}
