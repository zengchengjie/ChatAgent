package com.chatagent.config;

import com.chatagent.security.AgentRateLimitFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@RequiredArgsConstructor
public class RateLimitFilterConfig {

    private final StringRedisTemplate stringRedisTemplate;
    private final AppProperties appProperties;

    @Bean
    public AgentRateLimitFilter agentRateLimitFilter() {
        return new AgentRateLimitFilter(stringRedisTemplate, appProperties);
    }
}
