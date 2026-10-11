package com.gametrans.llm;

/** OpenAI 응답의 usage를 받는 곳. 클라이언트는 어디에 저장되는지 모른다. */
@FunctionalInterface
public interface UsageSink {
    record Usage(String stage, String model, int promptTokens, int completionTokens, int reasoningTokens) {}
    void record(Usage usage);
}
