package com.chatagent.it.model;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模型健康检查服务：定期检查 DashScope 模型可用性
 */
@Service
@Slf4j
public class ModelHealthCheckService {

    private final ModelRouterService modelRouterService;

    // 健康状态
    private volatile boolean healthy = true;
    private volatile LocalDateTime lastCheckTime = LocalDateTime.now();
    private volatile String lastReason = "Initialized";
    private final AtomicInteger failureCount = new AtomicInteger(0);

    // 配置
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final String HEALTH_CHECK_PROMPT = "请回复'OK'";

    public ModelHealthCheckService(ModelRouterService modelRouterService) {
        this.modelRouterService = modelRouterService;
    }

    /**
     * 定时健康检查（每5分钟执行一次）
     */
    @Scheduled(fixedDelay = 300000)
    public void scheduledHealthCheck() {
        log.debug("Starting scheduled health check for DashScope");

        try {
            ChatLanguageModel model = modelRouterService.getModel();
            long startTime = System.currentTimeMillis();
            String response = model.generate(List.of(UserMessage.from(HEALTH_CHECK_PROMPT))).content().text();
            long elapsed = System.currentTimeMillis() - startTime;

            if (response != null && !response.isBlank()) {
                log.debug("DashScope health check passed in {}ms", elapsed);
                setHealthy("Health check passed in " + elapsed + "ms");
                recordSuccess();
            } else {
                log.warn("DashScope health check returned empty response");
                recordFailure("Empty response from health check");
            }
        } catch (Exception e) {
            log.error("DashScope health check failed: {}", e.getMessage());
            recordFailure("Health check exception: " + e.getMessage());
        }

        logStatus();
    }

    /**
     * 手动触发健康检查
     */
    public void triggerHealthCheck() {
        log.info("Manual health check triggered for DashScope");
        scheduledHealthCheck();
    }

    /**
     * 记录模型使用失败
     */
    public void recordFailure(String errorMessage) {
        int failures = failureCount.incrementAndGet();
        log.warn("DashScope failure recorded (count: {}): {}", failures, errorMessage);

        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            setUnhealthy("Exceeded max consecutive failures: " + failures);
            failureCount.set(0);
        }
    }

    /**
     * 记录模型使用成功
     */
    public void recordSuccess() {
        failureCount.set(0);
        if (!healthy) {
            log.info("DashScope succeeded after being unhealthy, recovering");
            setHealthy("Recovered after success");
        }
    }

    /**
     * 获取模型是否健康
     */
    public boolean isHealthy() {
        return healthy;
    }

    /**
     * 获取健康状态详情
     */
    public HealthStatus getHealthStatus() {
        return new HealthStatus(healthy, lastCheckTime, lastReason);
    }

    // ========== 私有方法 ==========

    private synchronized void setHealthy(String reason) {
        healthy = true;
        lastCheckTime = LocalDateTime.now();
        lastReason = reason;
        log.info("DashScope marked as HEALTHY: {}", reason);
    }

    private synchronized void setUnhealthy(String reason) {
        healthy = false;
        lastCheckTime = LocalDateTime.now();
        lastReason = reason;
        log.warn("DashScope marked as UNHEALTHY: {}", reason);
    }

    private void logStatus() {
        log.info("DashScope health status: {} (reason: {})",
            healthy ? "HEALTHY" : "UNHEALTHY", lastReason);
    }

    public record HealthStatus(
        boolean healthy,
        LocalDateTime lastCheckTime,
        String reason
    ) {}
}
