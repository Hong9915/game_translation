package com.gametrans.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(OpenAi openai, Storage storage, Translate translate, int uploadMaxMb, String corsOrigins) {
    /**
     * model/reasoningEffort: 번역·단축(품질 중요). lightModel/lightReasoningEffort: 고유명사 추출·후보 추천(단순, 속도 중요).
     */
    public record OpenAi(String apiKey, String model, String baseUrl, String reasoningEffort,
                         String lightModel, String lightReasoningEffort, String prices) {
        public String lightModelOrDefault() { return lightModel == null || lightModel.isBlank() ? model : lightModel; }
    }
    public record Storage(String type, String localDir, String supabaseUrl, String supabaseServiceKey, String supabaseBucket) {}
    /** engine: "deepl"(기본) | "gpt". 번역(파일·빠른·행)에 쓸 엔진 — 용어 고치기·명령·줄이기·교정은 늘 GPT. */
    public record Translate(int batchSize, double lengthRatioDefault, String engine) {
        public boolean deepl() { return engine == null || engine.isBlank() || "deepl".equalsIgnoreCase(engine.trim()); }
    }
}
