package com.chatagent.it;

import java.time.Instant;
import java.util.List;

public record UserMemoryEntry(
        String id,
        String userId,
        String content,
        MemoryType type,
        List<String> tags,
        Instant createdAt
) {
    public enum MemoryType {
        /** 姓名、部门、设备等客观事实 */
        fact,
        /** UI偏好、沟通习惯等 */
        preference,
        /** 用户教给 Agent 的知识 */
        knowledge
    }
}
