package com.demo.upimesh.config;

import com.demo.upimesh.security.ApiKeyFilter;
import com.demo.upimesh.security.RateLimitFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableScheduling
public class AppConfig {

    @Value("${cors.allowed-origin:http://localhost:8080}")
    private String allowedOrigin;

    @Autowired private ApiKeyFilter    apiKeyFilter;
    @Autowired private RateLimitFilter rateLimitFilter;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(allowedOrigin)
                        .allowedMethods("GET", "POST")
                        .allowedHeaders("Content-Type", "Authorization", "X-Bridge-Node-Id", "X-Hop-Count")
                        .maxAge(3600);
            }
        };
    }

    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilterReg() {
        FilterRegistrationBean<ApiKeyFilter> reg = new FilterRegistrationBean<>(apiKeyFilter);
        reg.addUrlPatterns("/api/*");
        reg.setOrder(1);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterReg() {
        FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>(rateLimitFilter);
        reg.addUrlPatterns("/api/bridge/ingest");
        reg.setOrder(2);
        return reg;
    }
}
