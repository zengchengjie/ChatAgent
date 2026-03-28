package com.chatagent.common;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final StringRedisTemplate redis;

    public record IdempotencyResult(boolean acquired, String value) {}

    public IdempotencyResult tryAcquire(String key, Duration ttl) {
        Boolean ok = redis.opsForValue().setIfAbsent(key, "__processing__", ttl);
        if (Boolean.TRUE.equals(ok)) {
            return new IdempotencyResult(true, null);
        }
        String existing = redis.opsForValue().get(key);
        return new IdempotencyResult(false, existing);
    }

    public void storeResult(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }
}

