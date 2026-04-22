package com.chatagent.it.model;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模型健康检查服务：定期检查模型可用性，实现自动降级和恢复
 */
@Service
@Slf4j
public class ModelHealthCheckService {

    private final ModelRouterService modelRouterService;

    // 健康检查状态
    private final Map<ModelRouterService.ModelType, HealthStatus> healthStatus = new ConcurrentHashMap<>();

    // 失败计数
    private final Map<ModelRouterService.ModelType, AtomicInteger> failureCounts = new ConcurrentHashMap<>();

    // 配置
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final long RECOVERY_CHECK_INTERVAL_MS = 30000; // 30秒
    private static final String HEALTH_CHECK_PROMPT = "请回复'OK'";

    public ModelHealthCheckService(ModelRouterService modelRouterService) {
        this.modelRouterService = modelRouterService;

        // 初始化状态
        for (ModelRouterService.ModelType type : ModelRouterService.ModelType.values()) {
            healthStatus.put(type, new HealthStatus(true, LocalDateTime.now(), "Initialized"));
            failureCounts.put(type, new AtomicInteger(0));
        }
    }

    /**
     * 定时健康检查（每5分钟执行一次）
     */
    @Scheduled(fixedDelay = 300000) // 5分钟
    public void scheduledHealthCheck() {
        log.debug("Starting scheduled health check for all models");

        for (ModelRouterService.ModelType type : ModelRouterService.ModelType.values()) {
            checkModelHealth(type);
        }

        logStatus();
    }

    /**
     * 手动触发健康检查
     */
    public void triggerHealthCheck(ModelRouterService.ModelType type) {
        log.info("Manual health check triggered for model: {}", type);
        checkModelHealth(type);
    }

    /**
     * 记录模型使用失败
     */
    public void recordFailure(ModelRouterService.ModelType type, String errorMessage) {
        AtomicInteger count = failureCounts.get(type);
        int failures = count.incrementAndGet();

        log.warn("Model {} failure recorded (count: {}): {}", type, failures, errorMessage);

        // 如果连续失败次数超过阈值，标记为不健康
        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            setUnhealthy(type, "Exceeded max consecutive failures: " + failures);
            count.set(0); // 重置计数
        }

        // 更新健康状态
        HealthStatus status = healthStatus.get(type);
        healthStatus.put(type, new HealthStatus(
            status.healthy(),
            status.lastCheckTime(),
            "Last error: " + errorMessage
        ));
    }

    /**
     * 记录模型使用成功
     */
    public void recordSuccess(ModelRouterService.ModelType type) {
        failureCounts.get(type).set(0); // 重置失败计数

        // 如果之前不健康，检查是否应该恢复
        HealthStatus status = healthStatus.get(type);
        if (!status.healthy()) {
            log.info("Model {} succeeded after being unhealthy, considering recovery", type);
            // 成功一次就恢复（或者可以要求连续成功多次）
            setHealthy(type, "Recovered after success");
        }
    }

    /**
     * 获取模型健康状态
     */
    public boolean isHealthy(ModelRouterService.ModelType type) {
        HealthStatus status = healthStatus.get(type);
        return status != null && status.healthy();
    }

    /**
     * 获取所有模型健康状态
     */
    public Map<ModelRouterService.ModelType, HealthStatus> getAllHealthStatus() {
        return Map.copyOf(healthStatus);
    }

    /**
     * 强制设置模型健康状态
     */
    public void setModelHealth(ModelRouterService.ModelType type, boolean healthy, String reason) {
        if (healthy) {
            setHealthy(type, reason);
        } else {
            setUnhealthy(type, reason);
        }
    }

    // ========== 私有方法 ==========

    private void checkModelHealth(ModelRouterService.ModelType type) {
        log.debug("Checking health for model: {}", type);

        try {
            // 获取模型实例
            ChatLanguageModel model = modelRouterService.getModel(type);

            // 发送简单的健康检查请求
            long startTime = System.currentTimeMillis();
            String response = model.generate(List.of(UserMessage.from(HEALTH_CHECK_PROMPT))).content().text();
            long elapsed = System.currentTimeMillis() - startTime;

            // 检查响应是否有效
            boolean healthy = response != null && !response.isBlank();

            if (healthy) {
                log.debug("Model {} health check passed in {}ms", type, elapsed);
                setHealthy(type, "Health check passed in " + elapsed + "ms");
                recordSuccess(type);
            } else {
                log.warn("Model {} health check returned empty response", type);
                recordFailure(type, "Empty response from health check");
            }

        } catch (Exception e) {
            log.error("Model {} health check failed: {}", type, e.getMessage());
            recordFailure(type, "Health check exception: " + e.getMessage());
        }
    }

    private void setHealthy(ModelRouterService.ModelType type, String reason) {
        HealthStatus newStatus = new HealthStatus(true, LocalDateTime.now(), reason);
        healthStatus.put(type, newStatus);
        modelRouterService.setModelHealthy(type, true);
        log.info("Model {} marked as HEALTHY: {}", type, reason);
    }

    private void setUnhealthy(ModelRouterService.ModelType type, String reason) {
        HealthStatus newStatus = new HealthStatus(false, LocalDateTime.now(), reason);
        healthStatus.put(type, newStatus);
        modelRouterService.setModelHealthy(type, false);
        log.warn("Model {} marked as UNHEALTHY: {}", type, reason);
    }

    private void logStatus() {
        if (log.isInfoEnabled()) {
            StringBuilder sb = new StringBuilder("Model health status:\n");
            for (Map.Entry<ModelRouterService.ModelType, HealthStatus> entry : healthStatus.entrySet()) {
                HealthStatus status = entry.getValue();
                sb.append(String.format("  %s: %s (last check: %s, reason: %s)%n",
                    entry.getKey(),
                    status.healthy() ? "HEALTHY" : "UNHEALTHY",
                    status.lastCheckTime(),
                    status.reason()));
            }
            log.info(sb.toString());
        }
    }

    // ========== 记录类 ==========

    public record HealthStatus(
        boolean healthy,
        LocalDateTime lastCheckTime,
        String reason
    ) {}
}