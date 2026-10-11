package com.gametrans.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class WebConfig implements WebMvcConfigurer {
    private final AppProperties props;

    public WebConfig(AppProperties props) { this.props = props; }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(props.corsOrigins().split(","))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE")
                .exposedHeaders("Content-Disposition");
    }
}
