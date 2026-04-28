package com.chatagent.it.model;

import com.chatagent.config.DashScopeProperties;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * 模型路由服务（简化版）：仅提供 DashScope 模型实例
 */
@Service
@Slf4j
public class ModelRouterService {

    private final DashScopeProperties dashscopeProperties;

    // DashScope 模型实例缓存
    private volatile ChatLanguageModel dashscopeModel;

    public ModelRouterService(DashScopeProperties dashscopeProperties) {
        this.dashscopeProperties = dashscopeProperties;
    }

    /**
     * 获取 DashScope 模型实例
     */
    public ChatLanguageModel getModel() {
        if (dashscopeModel == null) {
            synchronized (this) {
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
                    log.info("DashScope model initialized: {}", dashscopeProperties.getModel());
                }
            }
        }
        return dashscopeModel;
    }

    /**
     * 获取当前使用的模型名称
     */
    public String getModelName() {
        return dashscopeProperties.getModel();
    }

    /**
     * 模型上下文（任务类型标识）
     */
    public record ModelContext(
        boolean qualityFirst,
        String taskType
    ) {
        public ModelContext() {
            this(true, "general");
        }

        public ModelContext(String taskType) {
            this(true, taskType);
        }
    }
}
