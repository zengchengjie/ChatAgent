package com.chatagent.it.model;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 混合模型服务：封装模型选择、调用、降级和重试逻辑
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HybridModelService {

    private final ModelRouterService modelRouterService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 智能生成回复：根据查询自动选择最佳模型
     *
     * @param query 用户查询
     * @param context 模型上下文
     * @return 生成的回复
     */
    public String generate(String query, ModelRouterService.ModelContext context) {
        ModelRouterService.ModelType selectedType = modelRouterService.selectModel(query, context);
        ChatLanguageModel model = modelRouterService.getModel(selectedType);

        log.debug("Selected model: {} for query: {}", selectedType, query.substring(0, Math.min(50, query.length())));

        try {
            String response = model.generate(List.of(UserMessage.from(query))).content().text();
            modelRouterService.recordUsage(selectedType, true);
            return response;
        } catch (Exception e) {
            log.warn("Model {} failed: {}, trying fallback", selectedType, e.getMessage());
            modelRouterService.recordUsage(selectedType, false);

            // 降级到另一个模型
            return fallbackGenerate(query, context, selectedType);
        }
    }

    /**
     * 带超时的生成（适用于实时交互）
     */
    public String generateWithTimeout(String query, ModelRouterService.ModelContext context, long timeoutMs) {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(
            () -> generate(query, context),
            executor
        );

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Model generation timeout after {}ms, using fallback", timeoutMs);
            future.cancel(true);
            return fallbackToSimpleModel(query, context);
        } catch (Exception e) {
            log.warn("Model generation failed: {}, using fallback", e.getMessage());
            return fallbackToSimpleModel(query, context);
        }
    }

    /**
     * 并行调用两个模型，返回第一个成功的结果
     */
    public String generateWithRace(String query, ModelRouterService.ModelContext context) {
        CompletableFuture<String> dashscopeFuture = CompletableFuture.supplyAsync(() -> {
            try {
                ChatLanguageModel model = modelRouterService.getModel(ModelRouterService.ModelType.DASHSCOPE);
                String response = model.generate(List.of(UserMessage.from(query))).content().text();
                modelRouterService.recordUsage(ModelRouterService.ModelType.DASHSCOPE, true);
                return response;
            } catch (Exception e) {
                modelRouterService.recordUsage(ModelRouterService.ModelType.DASHSCOPE, false);
                throw new RuntimeException(e);
            }
        }, executor);

        CompletableFuture<String> ollamaFuture = CompletableFuture.supplyAsync(() -> {
            try {
                ChatLanguageModel model = modelRouterService.getModel(ModelRouterService.ModelType.OLLAMA);
                String response = model.generate(List.of(UserMessage.from(query))).content().text();
                modelRouterService.recordUsage(ModelRouterService.ModelType.OLLAMA, true);
                return response;
            } catch (Exception e) {
                modelRouterService.recordUsage(ModelRouterService.ModelType.OLLAMA, false);
                throw new RuntimeException(e);
            }
        }, executor);

        CompletableFuture<Object> race = CompletableFuture.anyOf(dashscopeFuture, ollamaFuture);

        try {
            String result = (String) race.get(10, TimeUnit.SECONDS);
            log.debug("Race completed, first successful model returned result");
            return result;
        } catch (TimeoutException e) {
            log.warn("Both models timeout in race");
            return "请求超时，请稍后重试";
        } catch (Exception e) {
            log.warn("Both models failed in race: {}", e.getMessage());
            return fallbackToSimpleModel(query, context);
        } finally {
            // 取消另一个未完成的任务
            dashscopeFuture.cancel(true);
            ollamaFuture.cancel(true);
        }
    }

    /**
     * 专门用于路由决策的生成（使用轻量模型）
     */
    public String generateForRouting(String query) {
        // 路由决策总是使用Ollama（轻量、快速、低成本）
        ModelRouterService.ModelContext context = new ModelRouterService.ModelContext(
            ModelRouterService.ModelType.OLLAMA,
            true,  // 成本敏感
            false, // 不需要最高质量
            "routing"
        );

        try {
            return generate(query, context);
        } catch (Exception e) {
            log.error("Routing model failed, using default routing", e);
            // 如果路由模型失败，返回一个默认的路由决策（搜索知识库）
            return "{\"tool\": \"searchKnowledgeBase\", \"input\": \"" + query + "\"}";
        }
    }

    // ========== 私有方法 ==========

    private String fallbackGenerate(String query, ModelRouterService.ModelContext context, ModelRouterService.ModelType failedType) {
        ModelRouterService.ModelType fallbackType = (failedType == ModelRouterService.ModelType.DASHSCOPE) ?
                ModelRouterService.ModelType.OLLAMA : ModelRouterService.ModelType.DASHSCOPE;

        log.info("Falling back from {} to {}", failedType, fallbackType);

        try {
            ChatLanguageModel fallbackModel = modelRouterService.getModel(fallbackType);
            String response = fallbackModel.generate(List.of(UserMessage.from(query))).content().text();
            modelRouterService.recordUsage(fallbackType, true);
            return response;
        } catch (Exception e) {
            log.error("Fallback model {} also failed: {}", fallbackType, e.getMessage());
            modelRouterService.recordUsage(fallbackType, false);

            // 两个模型都失败了，返回兜底回复
            return getFallbackResponse(query);
        }
    }

    private String fallbackToSimpleModel(String query, ModelRouterService.ModelContext context) {
        // 尝试使用Ollama作为最后的兜底（假设它更稳定）
        try {
            ChatLanguageModel model = modelRouterService.getModel(ModelRouterService.ModelType.OLLAMA);
            String response = model.generate(List.of(UserMessage.from(query))).content().text();
            modelRouterService.recordUsage(ModelRouterService.ModelType.OLLAMA, true);
            return response;
        } catch (Exception e) {
            log.error("Even fallback to Ollama failed: {}", e.getMessage());
            return getFallbackResponse(query);
        }
    }

    private String getFallbackResponse(String query) {
        // 根据查询内容返回一个简单的兜底回复
        if (query.contains("网络") || query.contains("VPN") || query.contains("Wi-Fi")) {
            return "检测到网络相关问题，请尝试重启路由器或联系网络管理员。";
        } else if (query.contains("密码") || query.contains("登录") || query.contains("账号")) {
            return "账号密码问题请联系IT支持部门重置。";
        } else if (query.contains("软件") || query.contains("安装") || query.contains("卸载")) {
            return "软件安装问题请通过公司软件中心操作，或提交工单申请。";
        } else {
            return "我已收到您的请求，但由于系统暂时无法处理复杂查询，请稍后重试或联系人工客服。";
        }
    }

    /**
     * 获取模型路由服务的统计信息
     */
    public ModelRouterService.ModelStats getStats() {
        return modelRouterService.getStats();
    }

    /**
     * 手动设置模型健康状态
     */
    public void setModelHealthy(ModelRouterService.ModelType type, boolean healthy) {
        modelRouterService.setModelHealthy(type, healthy);
    }
}