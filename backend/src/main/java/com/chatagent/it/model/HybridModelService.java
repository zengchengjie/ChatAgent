package com.chatagent.it.model;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模型服务：封装 DashScope 模型调用和重试逻辑
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HybridModelService {

    private final ModelRouterService modelRouterService;
    private final ModelHealthCheckService healthCheckService;

    /**
     * 生成回复
     */
    public String generate(String query, ModelRouterService.ModelContext context) {
        ChatLanguageModel model = modelRouterService.getModel();

        log.debug("Generating response for query: {}", query.substring(0, Math.min(50, query.length())));

        try {
            String response = model.generate(List.of(UserMessage.from(query))).content().text();
            healthCheckService.recordSuccess();
            return response;
        } catch (Exception e) {
            log.warn("DashScope generation failed: {}, retrying once", e.getMessage());
            healthCheckService.recordFailure(e.getMessage());
        }

        // 重试一次
        try {
            ChatLanguageModel model2 = modelRouterService.getModel();
            String response = model2.generate(List.of(UserMessage.from(query))).content().text();
            healthCheckService.recordSuccess();
            return response;
        } catch (Exception e) {
            log.error("DashScope retry also failed: {}", e.getMessage());
            healthCheckService.recordFailure(e.getMessage());
            return getFallbackResponse(query);
        }
    }

    /**
     * 路由决策（使用 DashScope）
     */
    public String generateForRouting(String query) {
        try {
            ChatLanguageModel model = modelRouterService.getModel();
            return model.generate(List.of(UserMessage.from(query))).content().text();
        } catch (Exception e) {
            log.error("Routing model failed, using default routing", e);
            healthCheckService.recordFailure(e.getMessage());
            return "{\"tool\": \"searchKnowledgeBase\", \"input\": \"" + query + "\"}";
        }
    }

    private String getFallbackResponse(String query) {
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
}
