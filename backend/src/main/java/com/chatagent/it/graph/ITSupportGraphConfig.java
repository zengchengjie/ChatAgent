package com.chatagent.it.graph;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

import com.chatagent.it.ITSupportTools;
import com.chatagent.it.model.HybridModelService;
import com.chatagent.it.model.ModelRouterService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
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
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
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
    private final HybridModelService hybridModelService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Pattern jsonPattern = Pattern.compile("\\{[\\s\\S]*\\}", Pattern.MULTILINE);

    // 预处理：纯问候语/闲聊/身份询问等（绕过 LLM 路由，直接回复）
    private static final Pattern GREETING_PATTERN = Pattern.compile(
        "^(你好|您好|嗨|hi|hello|hey|早上好|上午好|中午好|下午好|晚上好|谢谢|感谢|多谢|辛苦了|再见|拜拜|bye|good\\s*morning|good\\s*afternoon|good\\s*evening|你是谁|你叫什么|你叫什么名字|你能做什么|你有什么功能|你会什么|你会做什么|在吗|在不在|好的谢谢|好的|好吧|ok|okay)[!！。.？?，,\\s]*$",
        Pattern.CASE_INSENSITIVE
    );

    private boolean isPureGreeting(String message) {
        if (message == null || message.isBlank()) return false;
        return GREETING_PATTERN.matcher(message.trim()).matches();
    }

    public ITSupportGraphConfig(Tracer tracer, HybridModelService hybridModelService) {
        this.tracer = tracer;
        this.hybridModelService = hybridModelService;
    }

    /** 内存版 Checkpoint Saver */
    @Bean
    MemorySaver checkpointSaver() {
        return new MemorySaver();
    }

    @Bean
    CompiledGraph<ITSupportGraphState> itSupportGraph(
            ITSupportTools tools,
            MemorySaver checkpointSaver) {
        try {
            StateGraph<ITSupportGraphState> sg = buildGraph(tools);
            return sg.compile(CompileConfig.builder()
                    .checkpointSaver(checkpointSaver)
                    .build());
        } catch (GraphStateException e) {
            throw new RuntimeException("Failed to build IT Support Graph", e);
        }
    }

    private StateGraph<ITSupportGraphState> buildGraph(
            ITSupportTools tools) throws GraphStateException {

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

        // router：分析意图，设置 nextNode（使用 DashScope 模型）
        graph.addNode(Nodes.ROUTER, routerNode(tools));
        // execute：执行工具，设置工具结果
        graph.addNode(Nodes.EXECUTE_TOOL, executeToolNode(tools));
        // pending：设置待审批信息，如果未审批则返回 END（触发调用方中断）
        graph.addNode(Nodes.PENDING_APPROVAL, pendingApprovalNode());
        // respond：返回最终结果（使用混合模型服务智能选择模型）
        graph.addNode(Nodes.RESPOND, respondNode());

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
            ITSupportTools tools) {
        return state -> {
            Span span = tracer.spanBuilder("router_node").startSpan();
            try (Scope scope = span.makeCurrent()) {
                String userMessage = state.lastUserMessage();
                span.setAttribute("user.message.length", userMessage.length());

                // 预处理：纯问候语/闲聊不走 LLM 路由，直接回复
                if (isPureGreeting(userMessage)) {
                    log.debug("Detected pure greeting, skipping LLM routing");
                    span.setAttribute("router.pre_filter", "greeting");
                    java.util.Map<String, Object> result = new java.util.HashMap<>();
                    result.put(CHANNEL_TOOL_NAME, "");
                    result.put(CHANNEL_ROUTER_OUTPUT, "");
                    result.put(CHANNEL_LLM_RESPONSE, "");  // 清空残留的上一轮回复
                    result.put(CHANNEL_NEEDS_APPROVAL, false);
                    return CompletableFuture.completedFuture(result);
                }

                // 为小模型优化的简化提示词
                String prompt =
                        """
                        分析用户问题，选择最合适的工具。只输出JSON，格式：{"tool": "工具名", "input": "参数"}
                        用户问题：「%s」

                        工具选项：
                        1. searchKnowledgeBase：IT流程、公司内部经验、联系谁、故障处理、VPN/网络问题
                        2. generateTicket：创建工单（需要问题摘要）
                        3. saveMemory：保存用户个人信息（设备、习惯、系统等）
                        4. searchMemory：搜索用户历史记忆
                        5. null：不需要工具，直接回复

                        简单规则：
                        - IT相关问题 → searchKnowledgeBase
                        - 用户说"我"、"我的"（陈述事实）→ saveMemory
                        - 用户问"什么"、"吗"、"怎么"、"记得" → searchMemory
                        - 用户要求创建工单 → generateTicket
                        - 打招呼、问候、感谢、告别等闲聊 → null
                        - 不确定 → searchKnowledgeBase

                        只输出JSON，不要其他文字。
                        """.formatted(userMessage);

                // 使用混合模型服务进行路由决策
                String response = hybridModelService.generateForRouting(prompt);

                span.setAttribute("llm.response.length", response.length());
                log.debug("LLM response: {}", response);

                String toolName = null;
                String toolInput = null;
                try {
                    // 提取 JSON 部分（去除 LLM 可能附加的前后缀文字）
                    Matcher m = jsonPattern.matcher(response);
                    if (m.find()) {
                        String jsonStr = m.group();
                        JsonNode node = objectMapper.readTree(jsonStr);
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
                    // JSON 解析失败时不崩溃，将 toolName 置为空，走直接回复路径
                    toolName = null;
                    toolInput = null;
                }

                span.setAttribute("tool.name", toolName != null ? toolName : "null");
                log.debug("Parsed toolName={}, toolInput={}", toolName, toolInput);

                if (toolName == null || toolName.isBlank() || "直接回复".equals(toolName)) {
                    java.util.Map<String, Object> result = new java.util.HashMap<>();
                    result.put(CHANNEL_TOOL_NAME, "");
                    result.put(CHANNEL_ROUTER_OUTPUT, response);
                    result.put(CHANNEL_NEEDS_APPROVAL, false);
                    return CompletableFuture.completedFuture(result);
                }

                // 需要审批的工具（仅工单需要审批，记忆自动保存）
                boolean needsApproval = "generateTicket".equals(toolName);

                // toolInput 为空时：工单需要摘要，搜索记忆需要用户消息作为查询
                if (toolInput == null || toolInput.isBlank()) {
                    if ("generateTicket".equals(toolName) && !userMessage.isBlank()) {
                        toolInput = userMessage;
                    } else if ("searchMemory".equals(toolName) && !userMessage.isBlank()) {
                        toolInput = userMessage;
                    } else {
                        toolInput = "";
                    }
                }
                java.util.Map<String, Object> result = new java.util.HashMap<>();
                result.put(CHANNEL_TOOL_NAME, toolName);
                result.put(CHANNEL_TOOL_INPUT, toolInput != null ? toolInput : "");
                result.put(CHANNEL_ROUTER_OUTPUT, response);
                result.put(CHANNEL_NEEDS_APPROVAL, needsApproval);
                span.setAttribute("router.tool_name", toolName);
                span.setAttribute("router.tool_input", toolInput != null ? toolInput : "");
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
                log.debug("Executing tool: {} with input: {}", toolName, toolInput);

                String result;
                try {
                    result =
                            switch (toolName) {
                                case "searchKnowledgeBase" -> tools.searchKnowledgeBase(toolInput);
                                case "generateTicket" -> {
                                    String userId = (String) state.value(CHANNEL_USER_ID).orElse("unknown");
                                    yield tools.generateTicket(userId, toolInput);
                                }
                                case "saveMemory" -> {
                                    String userId =
                                            (String) state.value(CHANNEL_USER_ID).orElse("unknown");
                                    String[] parts = toolInput.split("\\|");
                                    String content = parts.length > 0 ? parts[0] : "";
                                    String type = parts.length > 1 ? parts[1] : "fact";
                                    java.util.List<String> tags =
                                            parts.length > 2 ? List.of(parts[2].split(",")) : List.of();
                                    yield tools.saveMemory(userId, content, type, tags);
                                }
                                case "searchMemory" -> {
                                    String userId =
                                            (String) state.value(CHANNEL_USER_ID).orElse("unknown");
                                    yield tools.searchMemory(userId, toolInput);
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

    private AsyncNodeAction<ITSupportGraphState> respondNode() {
        return state -> {
            Span span = tracer.spanBuilder("respond_node").startSpan();
            try (Scope scope = span.makeCurrent()) {
                String toolResult = (String) state.value(CHANNEL_LLM_RESPONSE).orElse("");
                String userMessage = state.lastUserMessage();
                String toolName = (String) state.value(CHANNEL_TOOL_NAME).orElse("");

                log.debug("respondNode: toolName='{}', toolResult length={}, toolResult preview='{}'",
                    toolName, toolResult.length(), toolResult.length() > 50 ? toolResult.substring(0, 50) + "..." : toolResult);

                String finalResponse;
                // 直接返回的工具结果，不走 LLM 加工
                if ("saveMemory".equals(toolName)) {
                    finalResponse = toolResult != null && !toolResult.isBlank() ? toolResult : "记忆已保存";
                } else if ("generateTicket".equals(toolName) && toolResult != null && toolResult.startsWith("工单已创建")) {
                    finalResponse = toolResult;
                } else if ("searchMemory".equals(toolName) && toolResult != null && !toolResult.isBlank()) {
                    // searchMemory 结果已经是格式化好的，直接返回
                    finalResponse = toolResult;
                } else if ("searchKnowledgeBase".equals(toolName) && toolResult != null && !toolResult.isBlank()) {
                    // 知识库结果直接返回最相关的一段（RAG 按相似度排序，第一条最相关）
                    if (toolResult.contains("尚未初始化") || toolResult.contains("暂无相关内容")) {
                        String prompt =
                                """
                                你是一个企业 IT 支持助手，回复简洁中文，先结论后步骤。
                                用户消息：「%s」
                                请直接回复。
                                """.formatted(userMessage);
                        ModelRouterService.ModelContext context =
                            new ModelRouterService.ModelContext(
                                "direct_response_fallback");
                        finalResponse = hybridModelService.generate(prompt, context);
                    } else {
                        String primaryResult = toolResult.split("\n---\n")[0];
                        finalResponse = primaryResult;
                    }
                } else if (toolResult != null && !toolResult.isBlank()) {
                    // RAG/诊断结果需要 LLM 整理 - 使用混合模型服务
                    String prompt =
                            """
                            你是一个企业 IT 支持助手。用户提问了「%s」，以下是从知识库检索到的相关信息，请整理成简洁、口语化的回复，先给结论再说步骤，不要照搬原文格式。

                            知识库内容：
                            %s
                            """.formatted(userMessage, toolResult);

                    // 根据任务类型选择模型上下文
                    ModelRouterService.ModelContext context =
                        new ModelRouterService.ModelContext(
                            "response_generation"
                        );

                    finalResponse = hybridModelService.generate(prompt, context);
                } else {
                    // 直接回复用户消息 - 使用混合模型服务
                    String prompt =
                            """
                            你是一个企业 IT 支持助手，回复简洁中文，先结论后步骤。
                            用户消息：「%s」
                            请直接回复。
                            """.formatted(userMessage);

                    ModelRouterService.ModelContext context =
                        new ModelRouterService.ModelContext(
                            "direct_response"
                        );

                    finalResponse = hybridModelService.generate(prompt, context);
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
