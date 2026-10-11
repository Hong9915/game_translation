package com.gametrans;

import com.gametrans.llm.LlmClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class FakeLlmConfig {
    @Bean @Primary
    public LlmClient fakeLlmClient() { return new FakeLlmClient(); }

    @Bean(name = "jobExecutor")
    public java.util.concurrent.Executor syncJobExecutor() { return Runnable::run; }
}
