package com.gametrans;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class FakeJosaConfig {
    /** JosaClient이기도 하므로 이 빈 하나가 HttpJosaClient 대신 주입된다(Primary가 둘이면 모호해진다). */
    @Bean @Primary
    public FakeJosaClient fakeJosaClient() { return new FakeJosaClient(); }
}
