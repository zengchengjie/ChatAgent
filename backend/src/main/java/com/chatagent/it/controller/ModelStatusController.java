package com.chatagent.it.controller;

import com.chatagent.it.model.HybridModelService;
import com.chatagent.it.model.ModelHealthCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 模型状态监控控制器
 */
@RestController
@RequestMapping("/api/it-support/models")
@RequiredArgsConstructor
public class ModelStatusController {

    private final ModelHealthCheckService healthCheckService;

    /**
     * 获取模型状态
     */
    @GetMapping("/status")
    public Map<String, Object> getModelStatus() {
        var healthStatus = healthCheckService.getHealthStatus();

        return Map.of(
            "model", "qwen3.5-flash",
            "healthy", healthStatus.healthy(),
            "lastCheckTime", healthStatus.lastCheckTime().toString(),
            "reason", healthStatus.reason()
        );
    }

    /**
     * 手动触发健康检查
     */
    @PostMapping("/health-check")
    public String triggerHealthCheck() {
        healthCheckService.triggerHealthCheck();
        return "Health check triggered for qwen3.5-flash";
    }
}
