package com.chatagent.it;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ITSupportChatService {

    private final ITSupportAgent agent;
    private final KnowledgeBaseRagService ragService;
    private final Tracer tracer;

    public String chat(String sessionId, String message) {
        Span span = tracer.spanBuilder("chat_service.chat").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("session.id", sessionId);
            String text = message == null ? "" : message.trim();
            span.setAttribute("message.length", text.length());

            if (!text.isBlank() && shouldPreferKnowledgeBase(text)) {
                span.setAttribute("strategy", "knowledge_first");
                String kb = ragService.search(text);
                if (isKnowledgeHit(kb)) {
                    span.setAttribute("kb.hit", true);
                    // Make the response deterministic: if KB hits, always answer from it.
                    String pretty = formatKnowledgeAnswer(kb);
                    return """
                            结论：根据公司知识库，优先按如下方案处理。

                            步骤：
                            %s
                            """.formatted(pretty);
                }
                span.setAttribute("kb.hit", false);
            }

            span.setAttribute("strategy", "agent");
            return agent.chat(sessionId, text);
        } finally {
            span.end();
        }
    }

    private static boolean shouldPreferKnowledgeBase(String text) {
        // Company process / internal know-how / "怎么办" style questions should consult KB first.
        String s = text.toLowerCase();
        return s.contains("怎么办")
                || s.contains("如何")
                || s.contains("怎么")
                || s.contains("流程")
                || s.contains("谁负责")
                || s.contains("vpn")
                || s.contains("权限")
                || s.contains("账号");
    }

    private static boolean isKnowledgeHit(String kb) {
        if (kb == null) return false;
        String s = kb.trim();
        if (s.isBlank()) return false;
        return !s.contains("尚未初始化")
                && !s.contains("暂未检索到相关内容");
    }

    private static String formatKnowledgeAnswer(String kbRaw) {
        // Strip headings / separators from RAG output and present a clean, readable answer.
        String[] lines = kbRaw.split("\\R");
        StringBuilder cleaned = new StringBuilder();
        for (String line : lines) {
            if (line == null) continue;
            String t = line.trim();
            if (t.isBlank()) continue;
            if (t.equals("---")) continue;
            if (t.startsWith("#")) continue; // markdown headings
            cleaned.append(t).append('\n');
        }

        String s = cleaned.toString().trim();
        if (s.isBlank()) {
            return "1. 知识库命中为空，请联系 IT 支持确认知识条目内容。";
        }

        String desc = extractSection(s, "问题描述");
        String solution = extractSection(s, "官方解决方案");

        StringBuilder out = new StringBuilder();
        int step = 1;
        if (!desc.isBlank()) {
            out.append(step++).append(". 问题描述：").append(desc).append('\n');
        }
        if (!solution.isBlank()) {
            out.append(step++).append(". 解决方案：").append(solution).append('\n');
        }

        if (out.length() == 0) {
            // Fallback: present cleaned text as a single step, avoid a wall of text.
            out.append("1. 知识库建议：").append(s.replaceAll("\\s+", " ").trim()).append('\n');
        }

        return out.toString().trim();
    }

    private static String extractSection(String text, String sectionName) {
        // Supports formats like "**问题描述**：xxx" or "问题描述：xxx"
        String normalized = text.replace("**", "");
        String key = sectionName + "：";
        int idx = normalized.indexOf(key);
        if (idx < 0) {
            key = sectionName + ":";
            idx = normalized.indexOf(key);
            if (idx < 0) {
                return "";
            }
        }
        int start = idx + key.length();
        int end = normalized.length();
        // Stop at next known section marker if present.
        for (String next : new String[] {"官方解决方案：", "官方解决方案:", "问题描述：", "问题描述:"}) {
            int j = normalized.indexOf(next, start);
            if (j >= 0 && j < end) {
                end = j;
            }
        }
        return normalized.substring(start, end).trim().replaceAll("\\s+", " ");
    }
}
