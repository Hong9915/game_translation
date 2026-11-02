package com.gametrans;

import com.gametrans.deepl.MtClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class FakeMtConfig {
    @Bean @Primary
    public MtClient fakeMtClient() { return new FakeMtClient(); }
}
