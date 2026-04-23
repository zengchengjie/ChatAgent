package com.chatagent.it.model;

import com.chatagent.config.DashScopeProperties;
import com.chatagent.config.OllamaProperties;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 智能模型路由服务
 * 根据查询复杂度、成本、模型健康状态动态选择 Ollama 或 DashScope
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModelRouterService {

    private final DashScopeProperties dashscopeProperties;
    private final OllamaProperties ollamaProperties;
    @Lazy
    private final ModelHealthCheckService healthCheckService;

    // 模型健康状态（缓存，实际状态从healthCheckService获取）
    private final AtomicBoolean dashscopeHealthy = new AtomicBoolean(true);
    private final AtomicBoolean ollamaHealthy = new AtomicBoolean(true);

    // 使用统计
    private final AtomicInteger dashscopeUsageCount = new AtomicInteger(0);
    private final AtomicInteger ollamaUsageCount = new AtomicInteger(0);

    // 模型实例缓存
    private volatile ChatLanguageModel dashscopeModel;
    private volatile ChatLanguageModel ollamaModel;

    /**
     * 根据查询内容选择最合适的模型
     *
     * @param query 用户查询
     * @param context 上下文信息（可选）
     * @return 选择的模型类型
     */
    public ModelType selectModel(String query, ModelContext context) {
        // 1. 健康检查优先：如果某个模型不健康，使用另一个
        boolean dashscopeHealthy = healthCheckService.isHealthy(ModelType.DASHSCOPE);
        boolean ollamaHealthy = healthCheckService.isHealthy(ModelType.OLLAMA);

        // 更新缓存
        this.dashscopeHealthy.set(dashscopeHealthy);
        this.ollamaHealthy.set(ollamaHealthy);

        if (!dashscopeHealthy) {
            log.debug("DashScope unhealthy, falling back to Ollama");
            return ModelType.OLLAMA;
        }
        if (!ollamaHealthy) {
            log.debug("Ollama unhealthy, using DashScope");
            return ModelType.DASHSCOPE;
        }

        // 2. 根据查询复杂度选择
        Complexity complexity = analyzeComplexity(query);

        // 3. 根据上下文选择（如果有的话）
        if (context != null) {
            // 如果之前使用了某个模型且效果良好，可以继续使用
            if (context.preferredModel() != null) {
                return context.preferredModel();
            }

            // 成本敏感型任务使用本地模型
            if (context.costSensitive()) {
                return ModelType.OLLAMA;
            }

            // 高质量要求使用云端模型
            if (context.qualityFirst()) {
                return ModelType.DASHSCOPE;
            }
        }

        // 4. 默认策略：简单查询用Ollama，复杂查询用DashScope
        switch (complexity) {
            case SIMPLE:
                return ModelType.OLLAMA;
            case MEDIUM:
                // 中等复杂度可以随机选择或基于负载选择
                return loadBalance() ? ModelType.OLLAMA : ModelType.DASHSCOPE;
            case COMPLEX:
            default:
                return ModelType.DASHSCOPE;
        }
    }

    /**
     * 获取指定类型的模型实例
     */
    public ChatLanguageModel getModel(ModelType type) {
        switch (type) {
            case DASHSCOPE:
                return getDashscopeModel();
            case OLLAMA:
                return getOllamaModel();
            default:
                throw new IllegalArgumentException("Unknown model type: " + type);
        }
    }

    /**
     * 记录模型使用情况
     */
    public void recordUsage(ModelType type, boolean success) {
        if (!success) {
            // 使用失败，通知健康检查服务
            healthCheckService.recordFailure(type, "Model usage failed");
            log.warn("Model {} usage failed, recorded in health check", type);
        } else {
            // 使用成功，更新计数并通知健康检查服务
            healthCheckService.recordSuccess(type);
            if (type == ModelType.DASHSCOPE) {
                dashscopeUsageCount.incrementAndGet();
            } else {
                ollamaUsageCount.incrementAndGet();
            }
        }
    }

    /**
     * 更新模型健康状态缓存（供 ModelHealthCheckService 内部调用）
     */
    void updateModelHealthCache(ModelType type, boolean healthy) {
        if (type == ModelType.DASHSCOPE) {
            dashscopeHealthy.set(healthy);
        } else {
            ollamaHealthy.set(healthy);
        }
        log.debug("Model {} health cache updated to: {}", type, healthy);
    }

    /**
     * 手动设置模型健康状态（外部调用）
     */
    public void setModelHealthy(ModelType type, boolean healthy) {
        String reason = healthy ? "Manually set healthy" : "Manually set unhealthy";
        healthCheckService.setModelHealth(type, healthy, reason);
        // 缓存会由 healthCheckService 通过 updateModelHealthCache 更新
        log.info("Model {} health manually set to: {}", type, healthy);
    }

    /**
     * 获取模型使用统计
     */
    public ModelStats getStats() {
        return new ModelStats(
            dashscopeUsageCount.get(),
            ollamaUsageCount.get(),
            healthCheckService.isHealthy(ModelType.DASHSCOPE),
            healthCheckService.isHealthy(ModelType.OLLAMA)
        );
    }

    /**
     * 分析查询复杂度（公开方法）
     */
    public Complexity analyzeQueryComplexity(String query) {
        return analyzeComplexity(query);
    }

    // ========== 私有方法 ==========

    private synchronized ChatLanguageModel getDashscopeModel() {
        if (dashscopeModel == null) {
            dashscopeModel = OpenAiChatModel.builder()
                    .apiKey(dashscopeProperties.getApiKey())
                    .baseUrl(dashscopeProperties.getBaseUrl())
                    .modelName(dashscopeProperties.getModel())
                    .temperature(dashscopeProperties.getTemperature())
                    .timeout(Duration.ofMillis(dashscopeProperties.getReadTimeoutMs()))
                    .maxRetries(1)
                    .customHeaders(Map.of(
                        "X-DashScope-Version", "2023-06-01",
                        "Authorization", "Bearer " + dashscopeProperties.getApiKey()
                    ))
                    .build();
            log.info("DashScope model initialized");
        }
        return dashscopeModel;
    }

    private synchronized ChatLanguageModel getOllamaModel() {
        if (ollamaModel == null) {
            ollamaModel = OpenAiChatModel.builder()
                    .apiKey("ollama")  // Ollama 不需要真实 API key
                    .baseUrl(ollamaProperties.getBaseUrl())
                    .modelName(ollamaProperties.getModel())
                    .temperature(ollamaProperties.getTemperature())
                    .timeout(Duration.ofMillis(ollamaProperties.getReadTimeoutMs()))
                    .maxRetries(ollamaProperties.getMaxRetries())
                    .build();
            log.info("Ollama model initialized");
        }
        return ollamaModel;
    }

    private Complexity analyzeComplexity(String query) {
        if (query == null || query.trim().isEmpty()) {
            return Complexity.SIMPLE;
        }

        String text = query.trim();
        int length = text.length();

        // 简单规则：根据长度和内容判断复杂度
        if (length < 20) {
            return Complexity.SIMPLE;
        }

        // 检查是否包含复杂关键词
        boolean hasComplexKeywords = text.contains("如何") || text.contains("为什么") ||
                                   text.contains("解决方案") || text.contains("故障") ||
                                   text.contains("配置") || text.contains("设置") ||
                                   text.contains("优化") || text.contains("调试");

        // 检查是否包含多个问题
        int questionMarks = countOccurrences(text, "?") + countOccurrences(text, "？");
        boolean hasMultipleQuestions = questionMarks > 1;

        if (length > 100 || hasComplexKeywords || hasMultipleQuestions) {
            return Complexity.COMPLEX;
        } else if (length > 50) {
            return Complexity.MEDIUM;
        } else {
            return Complexity.SIMPLE;
        }
    }

    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }

    private boolean loadBalance() {
        // 简单的负载均衡：如果DashScope使用次数远多于Ollama，则使用Ollama
        int dashscopeCount = dashscopeUsageCount.get();
        int ollamaCount = ollamaUsageCount.get();

        if (dashscopeCount == 0 && ollamaCount == 0) {
            return Math.random() < 0.5; // 初始随机选择
        }

        // 计算使用比例
        double ratio = (double) dashscopeCount / (dashscopeCount + ollamaCount + 1);
        return ratio > 0.6; // 如果DashScope使用超过60%，则使用Ollama
    }

    // ========== 枚举和记录类 ==========

    public enum ModelType {
        OLLAMA,
        DASHSCOPE
    }

    public enum Complexity {
        SIMPLE,
        MEDIUM,
        COMPLEX
    }

    public record ModelContext(
        ModelType preferredModel,
        boolean costSensitive,
        boolean qualityFirst,
        String taskType
    ) {}

    public record ModelStats(
        int dashscopeUsageCount,
        int ollamaUsageCount,
        boolean dashscopeHealthy,
        boolean ollamaHealthy
    ) {}
}