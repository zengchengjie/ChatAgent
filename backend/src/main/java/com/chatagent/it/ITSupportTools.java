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
            name = "searchKnowledgeBase",
            value =
                    "知识库检索（RAG）：涉及公司 IT 流程、VPN/网络问题、内部经验或非标准操作时必须调用。用户问「怎么做」「谁负责」「内部流程」「连不上」等，应优先调用本工具。")
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
