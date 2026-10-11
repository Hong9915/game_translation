package com.gametrans.storage;

import com.gametrans.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class StorageConfig {
    @Bean
    public FileStorage fileStorage(AppProperties props, RestClient.Builder builder) {
        AppProperties.Storage s = props.storage();
        if ("supabase".equalsIgnoreCase(s.type())) {
            return new SupabaseStorage(builder, s.supabaseUrl(), s.supabaseServiceKey(), s.supabaseBucket());
        }
        return new LocalFileStorage(s.localDir());
    }
}
