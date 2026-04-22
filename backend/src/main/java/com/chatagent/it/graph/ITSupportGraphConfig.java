package com.chatagent.it.graph;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

import com.chatagent.config.DashScopeProperties;
import com.chatagent.it.ITSupportTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
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

    public static final String GRAPH_LLM_MODEL = "qwen3.5-flash";
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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Pattern jsonPattern = Pattern.compile("\\{[\\s\\S]*\\}", Pattern.MULTILINE);

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
                        Channels.appender(() -> new java.util.ArrayList<String>()),
                        CHANNEL_PENDING_ACTION,
                        Channels.base((o, n) -> n, () -> ""),
                        CHANNEL_APPROVED,
                        Channels.base((o, n) -> n, () -> false),
                        CHANNEL_LLM_RESPONSE,
                        Channels.base((o, n) -> n, () -> ""),
                        CHANNEL_TOOL_NAME,
                        Channels.base((o, n) -> n, () -> ""),
                        CHANNEL_TOOL_INPUT,
                        Channels.base((o, n) -> n, () -> ""),
                        CHANNEL_ROUTER_OUTPUT,
                        Channels.base((o, n) -> n, () -> ""),
                        CHANNEL_USER_ID,
                        Channels.base((o, n) -> n, () -> ""),
                        CHANNEL_SESSION_ID,
                        Channels.base((o, n) -> n, () -> ""),
                        CHANNEL_NEEDS_APPROVAL,
                        Channels.base((o, n) -> n, () -> false));

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

        // 条件边：从 execute_tool 路由（根据 needsApproval 决定是否需要审批）
        graph.addConditionalEdges(
                Nodes.EXECUTE_TOOL,
                (AsyncEdgeAction<ITSupportGraphState>)
                        state -> {
                            Boolean needsApproval =
                                    (Boolean) state.value(CHANNEL_NEEDS_APPROVAL).orElse(false);
                            return CompletableFuture.completedFuture(
                                    Boolean.TRUE.equals(needsApproval)
                                            ? Nodes.PENDING_APPROVAL
                                            : Nodes.RESPOND);
                        },
                Map.of(
                        Nodes.PENDING_APPROVAL,
                        Nodes.PENDING_APPROVAL,
                        Nodes.RESPOND,
                        Nodes.RESPOND));

        // 条件边：从 pending 审批路由
        graph.addConditionalEdges(
                Nodes.PENDING_APPROVAL,
                (AsyncEdgeAction<ITSupportGraphState>)
                        state -> {
                            Boolean needsApproval =
                                    (Boolean) state.value(CHANNEL_NEEDS_APPROVAL).orElse(false);
                            // needsApproval=true 表示”正在等待用户审批”，图在此中断（END）
                            // 审批接口会直接处理，不走图的逻辑
                            String next = needsApproval ? END : Nodes.RESPOND;
                            return CompletableFuture.completedFuture(next);
                        },
                Map.of(END, END, Nodes.RESPOND, Nodes.RESPOND));

        return graph;
    }

    // ===================== 节点实现 =====================

    private AsyncNodeAction<ITSupportGraphState> routerNode(
            ChatLanguageModel chatModel, ITSupportTools tools) {
        return state -> {
            Span span = tracer.spanBuilder("router_node").startSpan();
            try (Scope scope = span.makeCurrent()) {
                String userMessage = state.lastUserMessage();
                span.setAttribute("user.message.length", userMessage.length());

                String prompt =
                        """
                        判断用户意图，选择合适的工具。只输出纯 JSON，不要任何其他文字。
                        用户消息：「%s」

                        选项：
                        - searchKnowledgeBase：IT 流程、内部经验（包括联系谁、内部流程、故障处理经验等）**优先使用**
                        - diagnoseNetwork：网络问题（VPN、Wi-Fi、有线）**仅在能明确判断是纯网络配置问题时使用**
                        - generateTicket：创建工单，input 填用户问题摘要（必填）
                        - saveMemory：保存用户记忆，当用户提到自己的设备、品牌、习惯、系统、语言等个人信息时必须调用。格式：内容|type|标签，用 | 分隔。例如：「我用 MacBook」→ "我用MacBook|preference|Mac,设备"
                        - searchMemory：搜索用户记忆，当用户问「我记得」「之前说过」「我的xxx」时调用
                        - null（直接回复）：不需要工具

                        决策规则：
                        - 如果用户问题是公司IT相关（怎么联系IT、故障处理流程、内部经验）→ searchKnowledgeBase
                        - 如果能明确判断是纯网络配置问题（VPN参数、Wi-Fi密码、有线设置）且不涉及公司IT流程 → diagnoseNetwork
                        - 如果用户陈述（不是问句）自己的设备/品牌/习惯/系统等个人信息（首次透露，用了"我""我的"）→ saveMemory
                        - 如果用户问句询问自己的设备/习惯/之前说过什么（用了"什么""吗""怎么""记得"）→ searchMemory
                        - 如果用户明确要求创建工单 → generateTicket
                        - 混合问题（公司IT+网络）→ searchKnowledgeBase
                        - 不确定时 → searchKnowledgeBase

                        输出格式（仅 JSON，禁止其他文字）：
                        {"tool": "工具名或null", "input": "参数或null"}
                        """.formatted(userMessage);

                String response =
                        chatModel.generate(List.of(UserMessage.from(prompt))).content().text();

                span.setAttribute("llm.response.length", response.length());

                String toolName = null;
                String toolInput = null;
                try {
                    // 提取 JSON 部分（去除 LLM 可能附加的前后缀文字）
                    Matcher m = jsonPattern.matcher(response);
                    if (m.find()) {
                        JsonNode node = objectMapper.readTree(m.group());
                        JsonNode toolNode = node.get("tool");
                        if (toolNode != null && !toolNode.isNull()) {
                            toolName = toolNode.asText();
                        }
                        JsonNode inputNode = node.get("input");
                        if (inputNode != null && !inputNode.isNull()) {
                            toolInput = inputNode.asText();
                        }
                    }
                } catch (Exception e) {
                    span.recordException(e);
                }

                span.setAttribute("tool.name", toolName != null ? toolName : "null");

                if (toolName == null || toolName.isBlank() || "直接回复".equals(toolName)) {
                    java.util.Map<String, Object> result = new java.util.HashMap<>();
                    result.put(CHANNEL_TOOL_NAME, "");
                    result.put(CHANNEL_ROUTER_OUTPUT, response);
                    result.put(CHANNEL_NEEDS_APPROVAL, false);
                    return CompletableFuture.completedFuture(result);
                }

                // 需要审批的工具（仅工单需要审批，记忆自动保存）
                boolean needsApproval = "generateTicket".equals(toolName);

                // toolInput 为空时：用用户消息作为默认值（工单需要摘要）
                if ((toolInput == null || toolInput.isBlank()) && !userMessage.isBlank()) {
                    toolInput = userMessage;
                }

                java.util.Map<String, Object> result = new java.util.HashMap<>();
                result.put(CHANNEL_TOOL_NAME, toolName);
                result.put(CHANNEL_TOOL_INPUT, toolInput != null ? toolInput : "");
                result.put(CHANNEL_ROUTER_OUTPUT, response);
                result.put(CHANNEL_NEEDS_APPROVAL, needsApproval);
                return CompletableFuture.completedFuture(result);
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

            String actionDesc =
                    toolName != null
                            ? String.format(
                                            "[%s]\n输入: %s\n\n确认执行？yes=确认，no=拒绝",
                                            toolName, toolInput)
                            : "准备回复。确认发送？yes=确认，no=拒绝";

            return CompletableFuture.completedFuture(
                    Map.of(
                            CHANNEL_PENDING_ACTION,
                            actionDesc,
                            CHANNEL_NEEDS_APPROVAL,
                            true,
                            CHANNEL_APPROVED,
                            false
                            ));
        };
    }

    private AsyncNodeAction<ITSupportGraphState> respondNode(ChatLanguageModel chatModel) {
        return state -> {
            Span span = tracer.spanBuilder("respond_node").startSpan();
            try (Scope scope = span.makeCurrent()) {
                String toolResult = (String) state.value(CHANNEL_LLM_RESPONSE).orElse("");
                String userMessage = state.lastUserMessage();
                String toolName = (String) state.value(CHANNEL_TOOL_NAME).orElse("");

                String finalResponse;
                // 直接返回的工具结果，不走 LLM 加工
                if ("saveMemory".equals(toolName)) {
                    finalResponse = "记忆已保存";
                } else if ("generateTicket".equals(toolName) && toolResult != null && toolResult.startsWith("工单已创建")) {
                    finalResponse = toolResult;
                } else if (toolResult != null && !toolResult.isBlank()) {
                    // RAG/诊断结果需要 LLM 整理
                    String prompt =
                            """
                            你是一个企业 IT 支持助手。用户提问了「%s」，以下是从知识库检索到的相关信息，请整理成简洁、口语化的回复，先给结论再说步骤，不要照搬原文格式。

                            知识库内容：
                            %s
                            """.formatted(userMessage, toolResult);
                    finalResponse =
                            chatModel.generate(List.of(UserMessage.from(prompt))).content().text();
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

    public static final class Nodes {
        public static final String ROUTER = "router";
        public static final String EXECUTE_TOOL = "execute_tool";
        public static final String PENDING_APPROVAL = "pending_approval";
        public static final String RESPOND = "respond";
    }
}
