package com.chatagent.security;

import com.chatagent.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 仅拦截 {@code /api/agent/**}；须在 JWT 认证之后执行（见 Security 链顺序），按用户 ID + 分钟桶在 Redis 中计数，超限返回 429。
 */
@RequiredArgsConstructor
@Slf4j
public class AgentRateLimitFilter extends OncePerRequestFilter {

    private static final DateTimeFormatter MINUTE_KEY =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final StringRedisTemplate stringRedisTemplate;
    private final AppProperties appProperties;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/agent/")) {
            filterChain.doFilter(request, response);
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof JwtPrincipal p)) {
            filterChain.doFilter(request, response);
            return;
        }
        String minute = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).format(MINUTE_KEY);
        // 每用户每分钟一个 key；expire 防止冷 key 堆积
        String key = "rl:agent:user:" + p.userId() + ":" + minute;
        try {
            Long count = stringRedisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                stringRedisTemplate.expire(key, Duration.ofMinutes(2));
            }
            int limit = appProperties.getRateLimit().getAgentRequestsPerMinute();
            if (count != null && count > limit) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"Rate limit exceeded\"}");
                return;
            }
        } catch (RedisSystemException e) {
            // fail-open：Redis 短暂抖动/重连时不应影响主链路（对话/工具调用）。
            log.warn("event=rate_limit_redis_failed userId={} key={} err={}", p.userId(), key, e.toString());
        } catch (RuntimeException e) {
            // 兜底：避免连接超时等运行时异常把请求打成 500
            log.warn("event=rate_limit_failed_open userId={} key={} err={}", p.userId(), key, e.toString());
        }
        filterChain.doFilter(request, response);
    }
}
