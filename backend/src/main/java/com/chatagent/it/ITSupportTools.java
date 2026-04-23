package com.chatagent.it;

import dev.langchain4j.agent.tool.Tool;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ITSupportTools {

    private final KnowledgeBaseRagService ragService;
    private final UserMemoryService memoryService;
    private final AtomicInteger ticketCounter = new AtomicInteger(1);

    @Tool(
            name = "diagnoseNetwork",
            value =
                    "网络诊断工具：针对 Wi-Fi、VPN、有线连接等返回排查步骤。用户提到连不上、网络错误、VPN 失败等情况时调用。")
    public String diagnoseNetwork(String issueDescription) {
        String issue = issueDescription == null ? "" : issueDescription.toLowerCase();
        if (issue.contains("vpn")) {
            return "建议：1. 检查公司账号/密码是否过期；2. 退出 VPN 客户端后重新连接；3. 更换网络环境后重试。";
        }
        if (issue.contains("wifi")
                || issue.contains("wi-fi")
                || issue.contains("无线")
                || issue.contains("wlan")) {
            return "建议：1. 忘记网络后重新连接；2. 重启路由器；3. 检查并更新网卡驱动。";
        }
        if (issue.contains("wired")
                || issue.contains("ethernet")
                || issue.contains("cable")
                || issue.contains("有线")
                || issue.contains("网线")
                || issue.contains("以太网")) {
            return "建议：1. 检查网线是否插牢；2. 更换网线或交换机端口；3. 在系统中重置网络适配器。";
        }
        return "建议：1. 禁用再启用网络适配器；2. 运行系统网络诊断；3. 若仍无法解决请提交工单。";
    }

    @Tool(
            name = "searchKnowledgeBase",
            value =
                    "知识库检索（RAG）：涉及公司 IT 流程、内部经验或非标准操作时必须先调用。用户问「怎么做」「谁负责」「内部流程」等，应优先调用本工具。")
    public String searchKnowledgeBase(String query) {
        String result = ragService.search(query == null ? "" : query);
        System.out.println("=== RAG Result ===");
        System.out.println("Query: " + query);
        System.out.println("Result: " + result);
        System.out.println("=================");
        return result;
    }

    @Tool(
            name = "generateTicket",
            value = "工单工具：创建 IT 支持工单。用户明确要求创建/提交工单时调用。")
    public String generateTicket(String userId, String issueSummary) {
        String date = DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now());
        String suffix = String.format("%03d", ticketCounter.getAndIncrement());
        return "工单已创建：TKT-"
                + date
                + "-"
                + suffix
                + "（用户="
                + userId
                + "，问题摘要="
                + (issueSummary == null ? "" : issueSummary)
                + "）";
    }

    @Tool(
            name = "saveMemory",
            value =
                    "保存长期记忆：从对话中提炼并存储关键事实、偏好或知识。类型：fact（姓名/部门/设备等）、preference（界面或沟通习惯）、knowledge（用户提供的知识，如「我用 MacBook」）。用户透露可复用的个人信息时调用。content 须在 50 字以内。")
    public String saveMemory(String userId, String content, String type, List<String> tags) {
        if (content == null || content.isBlank()) return "内容为空，未保存";
        UserMemoryEntry.MemoryType memoryType;
        try {
            memoryType = UserMemoryEntry.MemoryType.valueOf(type.trim().toLowerCase());
        } catch (Exception e) {
            memoryType = UserMemoryEntry.MemoryType.fact;
        }
        String saved = memoryService.save(userId, content, memoryType, tags);
        if (saved != null) {
            System.out.println("=== Memory Saved ===");
            System.out.println("userId=" + userId + " id=" + saved);
            return "记忆已保存";
        } else {
            return "记忆已存在";
        }
    }

    @Tool(
            name = "searchMemory",
            value =
                    "检索用户长期记忆：对用户历史记忆做语义搜索，返回相关事实、偏好与知识作为上下文。")
    public String searchMemory(String userId, String query) {
        List<UserMemoryEntry> entries = memoryService.search(userId, query, 5);
        if (entries.isEmpty()) {
            return "未找到相关记忆。";
        }
        StringBuilder sb = new StringBuilder("【用户历史记忆】\n");
        for (UserMemoryEntry e : entries) {
            sb.append("- [").append(memoryTypeLabel(e.type())).append("] ").append(e.content());
            if (!e.tags().isEmpty()) {
                sb.append(" #").append(String.join(" #", e.tags()));
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private static String memoryTypeLabel(UserMemoryEntry.MemoryType type) {
        if (type == null) {
            return "未知";
        }
        return switch (type) {
            case fact -> "事实";
            case preference -> "偏好";
            case knowledge -> "知识";
        };
    }
}
