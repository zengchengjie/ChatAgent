package com.chatagent.it.controller;

import com.chatagent.it.model.HybridModelService;
import com.chatagent.it.model.ModelHealthCheckService;
import com.chatagent.it.model.ModelRouterService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 模型状态监控控制器
 */
@RestController
@RequestMapping("/api/it-support/models")
@RequiredArgsConstructor
public class ModelStatusController {

    private final HybridModelService hybridModelService;
    private final ModelHealthCheckService healthCheckService;
    private final ModelRouterService modelRouterService;

    /**
     * 获取所有模型状态
     */
    @GetMapping("/status")
    public Map<String, Object> getModelStatus() {
        var stats = hybridModelService.getStats();
        var healthStatus = healthCheckService.getAllHealthStatus();

        return Map.of(
            "stats", Map.of(
                "dashscopeUsageCount", stats.dashscopeUsageCount(),
                "ollamaUsageCount", stats.ollamaUsageCount(),
                "dashscopeHealthy", stats.dashscopeHealthy(),
                "ollamaHealthy", stats.ollamaHealthy()
            ),
            "healthStatus", healthStatus.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                    e -> e.getKey().name(),
                    e -> Map.of(
                        "healthy", e.getValue().healthy(),
                        "lastCheckTime", e.getValue().lastCheckTime().toString(),
                        "reason", e.getValue().reason()
                    )
                ))
        );
    }

    /**
     * 手动触发健康检查
     */
    @PostMapping("/health-check")
    public String triggerHealthCheck(@RequestParam(required = false) String model) {
        if (model == null || model.isEmpty()) {
            // 检查所有模型
            healthCheckService.getAllHealthStatus().keySet().forEach(
                type -> healthCheckService.triggerHealthCheck(type)
            );
            return "Health check triggered for all models";
        } else {
            try {
                ModelRouterService.ModelType type = ModelRouterService.ModelType.valueOf(model.toUpperCase());
                healthCheckService.triggerHealthCheck(type);
                return "Health check triggered for model: " + model;
            } catch (IllegalArgumentException e) {
                return "Invalid model type: " + model + ". Valid values: DASHSCOPE, OLLAMA";
            }
        }
    }

    /**
     * 手动设置模型健康状态
     */
    @PostMapping("/set-health")
    public String setModelHealth(
            @RequestParam String model,
            @RequestParam boolean healthy,
            @RequestParam(required = false, defaultValue = "Manual operation") String reason) {
        try {
            ModelRouterService.ModelType type = ModelRouterService.ModelType.valueOf(model.toUpperCase());
            healthCheckService.setModelHealth(type, healthy, reason);
            return String.format("Model %s health set to %s: %s", model, healthy, reason);
        } catch (IllegalArgumentException e) {
            return "Invalid model type: " + model + ". Valid values: DASHSCOPE, OLLAMA";
        }
    }

    /**
     * 获取模型路由策略信息
     */
    @GetMapping("/routing-info")
    public Map<String, Object> getRoutingInfo(@RequestParam String query) {
        ModelRouterService.ModelContext context = new ModelRouterService.ModelContext(
            null, false, false, "test"
        );
        ModelRouterService.ModelType selected = modelRouterService.selectModel(query, context);
        ModelRouterService.Complexity complexity = modelRouterService.analyzeQueryComplexity(query);

        return Map.of(
            "query", query,
            "selectedModel", selected.name(),
            "complexity", complexity.name(),
            "dashscopeHealthy", healthCheckService.isHealthy(ModelRouterService.ModelType.DASHSCOPE),
            "ollamaHealthy", healthCheckService.isHealthy(ModelRouterService.ModelType.OLLAMA),
            "queryLength", query.length()
        );
    }
}