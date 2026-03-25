package com.chatagent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@TestConfiguration
public class TestRedisConfig {

    @Bean
    @Primary
    @SuppressWarnings("unchecked")
    public StringRedisTemplate stringRedisTemplate() {
        StringRedisTemplate tpl = mock(StringRedisTemplate.class);
        ValueOperations<String, String> vo = mock(ValueOperations.class);
        when(tpl.opsForValue()).thenReturn(vo);
        when(vo.increment(anyString())).thenReturn(1L);
        when(tpl.expire(anyString(), any(Duration.class))).thenReturn(true);
        return tpl;
    }
}
