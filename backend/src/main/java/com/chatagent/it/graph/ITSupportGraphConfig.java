package com.chatagent.it.graph;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

import com.chatagent.config.DashScopeProperties;
import com.chatagent.it.ITSupportTools;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ITSupportGraphConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    public static final String GRAPH_LLM_MODEL = "qwen3.5-122b-a10b";
    public static final String CHANNEL_MESSAGES = "messages";
    public static final String CHANNEL_PENDING_ACTION = "pendingAction";
    public static final String CHANNEL_APPROVED = "approved";
    public static final String CHANNEL_LLM_RESPONSE = "llmResponse";
    public static final String CHANNEL_TOOL_NAME = "toolName";
    public static final String CHANNEL_TOOL_INPUT = "toolInput";
    public static final String CHANNEL_ROUTER_OUTPUT = "routerOutput";
    public static final String CHANNEL_USER_ID = "userId";
    public static final String CHANNEL_SESSION_ID = "sessionId";
    public static final String CHANNEL_NEEDS_APPROVAL = "needsApproval";

    private final Tracer tracer;

    public ITSupportGraphConfig(Tracer tracer) {
        this.tracer = tracer;
    }

    /** Graph 专用的大模型，用于路由决策 */
    @Bean
    ChatLanguageModel graphChatModel(DashScopeProperties properties) {
        return OpenAiChatModel.builder()
                .apiKey(properties.getApiKey())
                .baseUrl(properties.getBaseUrl())
                .modelName(GRAPH_LLM_MODEL)
                .temperature(0.3)
                .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .build();
    }

    /** 内存版 Checkpoint Saver */
    @Bean
    MemorySaver checkpointSaver() {
        return new MemorySaver();
    }

    @Bean
    CompiledGraph<ITSupportGraphState> itSupportGraph(
            ChatLanguageModel graphChatModel,
            ITSupportTools tools,
            MemorySaver checkpointSaver) {
        try {
            StateGraph<ITSupportGraphState> sg = buildGraph(graphChatModel, tools);
            return sg.compile(CompileConfig.builder()
                    .checkpointSaver(checkpointSaver)
                    .build());
        } catch (GraphStateException e) {
            throw new RuntimeException("Failed to build IT Support Graph", e);
        }
    }

    private StateGraph<ITSupportGraphState> buildGraph(
            ChatLanguageModel chatModel, ITSupportTools tools) throws GraphStateException {

        var channels =
                Map.<String, Channel<?>>of(
                        CHANNEL_MESSAGES,
                        Channels.appender(() -> new java.util.ArrayList<ChatMessage>()),
                        CHANNEL_PENDING_ACTION,
                        Channels.base((o, n) -> n, () -> null),
                        CHANNEL_APPROVED,
                        Channels.base((o, n) -> n, () -> null),
                        CHANNEL_LLM_RESPONSE,
                        Channels.base((o, n) -> n, () -> null),
                        CHANNEL_TOOL_NAME,
                        Channels.base((o, n) -> n, () -> null),
                        CHANNEL_TOOL_INPUT,
                        Channels.base((o, n) -> n, () -> null),
                        CHANNEL_ROUTER_OUTPUT,
                        Channels.base((o, n) -> n, () -> null),
                        CHANNEL_USER_ID,
                        Channels.base((o, n) -> n, () -> null),
                        CHANNEL_SESSION_ID,
                        Channels.base((o, n) -> n, () -> null),
                        CHANNEL_NEEDS_APPROVAL,
                        Channels.base((o, n) -> n, () -> null));

        StateGraph<ITSupportGraphState> graph =
                new StateGraph<>(channels, ITSupportGraphState::new);

        // router：分析意图，设置 nextNode
        graph.addNode(Nodes.ROUTER, routerNode(chatModel, tools));
        // execute：执行工具，设置工具结果
        graph.addNode(Nodes.EXECUTE_TOOL, executeToolNode(tools));
        // pending：设置待审批信息，如果未审批则返回 END（触发调用方中断）
        graph.addNode(Nodes.PENDING_APPROVAL, pendingApprovalNode());
        // respond：返回最终结果
        graph.addNode(Nodes.RESPOND, respondNode(chatModel));

        // 边
        graph.addEdge(START, Nodes.ROUTER);
        graph.addEdge(Nodes.EXECUTE_TOOL, Nodes.PENDING_APPROVAL);
        graph.addEdge(Nodes.RESPOND, END);

        // 条件边：从 router 路由到 execute 或 respond 或 pending
        graph.addConditionalEdges(
                Nodes.ROUTER,
                (AsyncEdgeAction<ITSupportGraphState>)
                        state -> {
                            Boolean needsApproval =
                                    (Boolean) state.value(CHANNEL_NEEDS_APPROVAL).orElse(null);
                            String toolName =
                                    (String) state.value(CHANNEL_TOOL_NAME).orElse(null);
                            String next;
                            if (needsApproval != null && needsApproval) {
                                next = Nodes.PENDING_APPROVAL;
                            } else if (toolName == null || toolName.isBlank()) {
                                next = Nodes.RESPOND;
                            } else {
                                next = Nodes.EXECUTE_TOOL;
                            }
                            return CompletableFuture.completedFuture(next);
                        },
                Map.of(
                        Nodes.EXECUTE_TOOL,
                        Nodes.EXECUTE_TOOL,
                        Nodes.RESPOND,
                        Nodes.RESPOND,
                        Nodes.PENDING_APPROVAL,
                        Nodes.PENDING_APPROVAL));

        // 条件边：从 pending 审批路由
        graph.addConditionalEdges(
                Nodes.PENDING_APPROVAL,
                (AsyncEdgeAction<ITSupportGraphState>)
                        state -> {
                            Boolean approved =
                                    (Boolean) state.value(CHANNEL_APPROVED).orElse(null);
                            String next = approved == null ? END : (approved ? Nodes.RESPOND : Nodes.ROUTER);
                            return CompletableFuture.completedFuture(next);
                        },
                Map.of(Nodes.RESPOND, Nodes.RESPOND, Nodes.ROUTER, Nodes.ROUTER));

        return graph;
    }

    // ===================== 节点实现 =====================

    private AsyncNodeAction<ITSupportGraphState> routerNode(
            ChatLanguageModel chatModel, ITSupportTools tools) {
        return state -> {
            Span span = tracer.spanBuilder("router_node").startSpan();
            try (Scope scope = span.makeCurrent()) {
                List<ChatMessage> messages = state.messages();
                String userMessage =
                        messages.isEmpty()
                                ? ""
                                : messages.get(messages.size() - 1) instanceof UserMessage u
                                        ? u.text()
                                        : "";

                span.setAttribute("user.message.length", userMessage.length());

                String prompt =
                        """
                        判断用户意图，选择合适的工具。
                        用户消息：「%s」

                        选项：
                        - diagnoseNetwork：网络问题（VPN、Wi-Fi、有线）
                        - searchKnowledgeBase：IT 流程、内部经验
                        - generateTicket：创建工单
                        - saveMemory：保存用户记忆（格式：内容|type|标签列表）
                        - searchMemory：搜索用户记忆
                        - null（直接回复）：不需要工具

                        仅输出 JSON：{"tool": "工具名或null", "input": "参数或null"}
                        """.formatted(userMessage);

                String response =
                        chatModel.generate(List.of(UserMessage.from(prompt))).content().text();

                span.setAttribute("llm.response.length", response.length());

                String toolName = extractJsonString(response, "tool");
                String toolInput = extractJsonString(response, "input");

                span.setAttribute("tool.name", toolName != null ? toolName : "null");

                if (toolName == null || toolName.isBlank() || toolName.equals("直接回复")) {
                    return CompletableFuture.completedFuture(
                            Map.of(
                                    CHANNEL_TOOL_NAME,
                                    (Object) null,
                                    CHANNEL_ROUTER_OUTPUT,
                                    response,
                                    CHANNEL_NEEDS_APPROVAL,
                                    false));
                }

                // 需要审批的工具（如 saveMemory、generateTicket）
                boolean needsApproval = "saveMemory".equals(toolName) || "generateTicket".equals(toolName);

                return CompletableFuture.completedFuture(
                        Map.of(
                                CHANNEL_TOOL_NAME,
                                toolName,
                                CHANNEL_TOOL_INPUT,
                                toolInput != null ? toolInput : "",
                                CHANNEL_ROUTER_OUTPUT,
                                response,
                                CHANNEL_NEEDS_APPROVAL,
                                needsApproval));
            } catch (Exception e) {
                span.recordException(e);
                throw e;
            } finally {
                span.end();
            }
        };
    }

    private AsyncNodeAction<ITSupportGraphState> executeToolNode(ITSupportTools tools) {
        return state -> {
            String toolName = (String) state.value(CHANNEL_TOOL_NAME).orElse(null);
            String toolInput = (String) state.value(CHANNEL_TOOL_INPUT).orElse("");

            if (toolName == null) {
                return CompletableFuture.completedFuture(Map.of());
            }

            Span span = tracer.spanBuilder("execute_tool." + toolName).startSpan();
            try (Scope scope = span.makeCurrent()) {
                span.setAttribute("tool.name", toolName);
                span.setAttribute("tool.input.length", toolInput.length());

                String result;
                try {
                    result =
                            switch (toolName) {
                                case "diagnoseNetwork" -> tools.diagnoseNetwork(toolInput);
                                case "searchKnowledgeBase" -> tools.searchKnowledgeBase(toolInput);
                                case "generateTicket" -> {
                                    String userId = (String) state.value(CHANNEL_USER_ID).orElse("unknown");
                                    yield tools.generateTicket(userId, toolInput);
                                }
                                case "saveMemory" -> {
                                    String sessionId =
                                            (String) state.value(CHANNEL_SESSION_ID).orElse("default");
                                    String[] parts = toolInput.split("\\|");
                                    String content = parts.length > 0 ? parts[0] : "";
                                    String type = parts.length > 1 ? parts[1] : "fact";
                                    java.util.List<String> tags =
                                            parts.length > 2 ? List.of(parts[2].split(",")) : List.of();
                                    tools.saveMemory(sessionId, content, type, tags);
                                    yield "记忆已保存";
                                }
                                case "searchMemory" -> {
                                    String sessionId =
                                            (String) state.value(CHANNEL_SESSION_ID).orElse("default");
                                    yield tools.searchMemory(sessionId, toolInput);
                                }
                                default -> "未知工具: " + toolName;
                            };
                    span.setAttribute("tool.result.length", result.length());
                } catch (Exception e) {
                    span.recordException(e);
                    result = "工具执行失败: " + e.getMessage();
                    span.setAttribute("tool.error", e.getClass().getSimpleName());
                }

                return CompletableFuture.completedFuture(Map.of(CHANNEL_LLM_RESPONSE, result));
            } finally {
                span.end();
            }
        };
    }

    private AsyncNodeAction<ITSupportGraphState> pendingApprovalNode() {
        return state -> {
            String toolName = (String) state.value(CHANNEL_TOOL_NAME).orElse(null);
            String toolInput = (String) state.value(CHANNEL_TOOL_INPUT).orElse("");
            String result = (String) state.value(CHANNEL_LLM_RESPONSE).orElse("");

            String actionDesc =
                    toolName != null
                            ? String.format(
                                            "[%s]\n输入: %s\n结果: %s\n\n确认执行？yes=确认，no=拒绝",
                                            toolName, toolInput, result)
                            : "准备回复。确认发送？yes=确认，no=拒绝";

            return CompletableFuture.completedFuture(
                    Map.of(
                            CHANNEL_PENDING_ACTION,
                            actionDesc,
                            CHANNEL_APPROVED,
                            null // null = 等待审批
                            ));
        };
    }

    private AsyncNodeAction<ITSupportGraphState> respondNode(ChatLanguageModel chatModel) {
        return state -> {
            Span span = tracer.spanBuilder("respond_node").startSpan();
            try (Scope scope = span.makeCurrent()) {
                String toolResult = (String) state.value(CHANNEL_LLM_RESPONSE).orElse("");
                String toolName = (String) state.value(CHANNEL_TOOL_NAME).orElse(null);
                List<ChatMessage> messages = state.messages();
                String userMessage =
                        messages.isEmpty()
                                ? ""
                                : messages.get(messages.size() - 1) instanceof UserMessage u
                                        ? u.text()
                                        : "";

                String finalResponse;
                if (toolResult != null && !toolResult.isBlank()) {
                    finalResponse = toolResult;
                } else {
                    String prompt =
                            """
                            你是一个企业 IT 支持助手，回复简洁中文，先结论后步骤。
                            用户消息：「%s」
                            请直接回复。
                            """.formatted(userMessage);
                    finalResponse =
                            chatModel.generate(List.of(UserMessage.from(prompt))).content().text();
                }

                span.setAttribute("response.length", finalResponse.length());
                return CompletableFuture.completedFuture(Map.of(CHANNEL_LLM_RESPONSE, finalResponse));
            } finally {
                span.end();
            }
        };
    }

    private static String extractJsonString(String json, String key) {
        try {
            int keyIdx = json.indexOf("\"" + key + "\"");
            if (keyIdx < 0) return null;
            int colonIdx = json.indexOf(':', keyIdx);
            int startQuote = json.indexOf('"', colonIdx + 1);
            int endQuote = json.indexOf('"', startQuote + 1);
            return json.substring(startQuote + 1, endQuote);
        } catch (Exception e) {
            return null;
        }
    }

    public static final class Nodes {
        public static final String ROUTER = "router";
        public static final String EXECUTE_TOOL = "execute_tool";
        public static final String PENDING_APPROVAL = "pending_approval";
        public static final String RESPOND = "respond";
    }
}
