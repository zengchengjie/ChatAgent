package com.chatagent.it;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.redis.RedisEmbeddingStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserMemoryService {

    private static final String KEY_IDS = "user:%s:memory:ids";
    private static final String KEY_ENTRY = "user:%s:memory:entry:%s";
    /** 超过 90 天的记忆权重衰减至 0.5 */
    private static final double DECAY_HALF_LIFE_DAYS = 90;
    private static final double DECAY_MIN_SCORE = 0.3;

    private final StringRedisTemplate redisTemplate;
    private final RedisEmbeddingStore embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;
    private final ITSupportProperties properties;

    /**
     * 保存用户记忆，自动去重（按内容 SHA-256 哈希）。
     * @param userId 用户 ID（从 sessionId 解析）
     * @param content 记忆内容
     * @param type 记忆类型
     * @param tags 标签列表
     * @return 保存成功返回记忆 ID；已存在返回 null
     */
    public String save(String userId, String content, UserMemoryEntry.MemoryType type, List<String> tags) {
        String hash = sha256(content.trim());
        // 检查是否已存在（遍历所有 entry 哈希）
        List<String> ids = redisTemplate.opsForList().range(String.format(KEY_IDS, userId), 0, -1);
        if (ids != null) {
            for (String id : ids) {
                String entryJson = redisTemplate.opsForValue().get(String.format(KEY_ENTRY, userId, id));
                if (entryJson != null && entryJson.contains(hash)) {
                    log.debug("Memory already exists for user={}, skip", userId);
                    return null;
                }
            }
        }

        String id = UUID.randomUUID().toString();
        UserMemoryEntry entry = new UserMemoryEntry(id, userId, content.trim(), type, List.copyOf(tags), Instant.now());

        try {
            String json = objectMapper.writeValueAsString(entry);
            redisTemplate.opsForList().rightPush(String.format(KEY_IDS, userId), id);
            redisTemplate.opsForValue().set(String.format(KEY_ENTRY, userId, id), json);

            // 同时存入向量库（用于语义检索）
            TextSegment segment = TextSegment.from(json);
            embeddingStore.add(embeddingModel.embed(segment).content(), segment);

            log.info("Saved memory id={} userId={} type={}", id, userId, type);
            return id;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize memory entry", e);
            return null;
        }
    }

    /**
     * 语义检索用户记忆，按相关性 + 时间衰减排序。
     * @param userId 用户 ID
     * @param query 查询文本
     * @param topK 返回数量
     */
    public List<UserMemoryEntry> search(String userId, String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        var queryEmbedding = embeddingModel.embed(query).content();
        var searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK * 3) // 多取一些，后面按衰减过滤
                .build();

        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(searchRequest).matches();
        List<UserMemoryEntry> results = new ArrayList<>();

        for (EmbeddingMatch<TextSegment> match : matches) {
            try {
                UserMemoryEntry entry = objectMapper.readValue(match.embedded().text(), UserMemoryEntry.class);
                if (!entry.userId().equals(userId)) {
                    continue; // 跨用户过滤
                }
                double decayedScore = decayedScore(match.score(), entry.createdAt());
                if (decayedScore < DECAY_MIN_SCORE) {
                    continue;
                }
                results.add(entry);
                if (results.size() >= topK) {
                    break;
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize memory entry", e);
            }
        }

        // 按衰减分数排序
        results.sort(Comparator.comparingDouble(e -> -decayedScore(e, e.createdAt())));
        return results;
    }

    /**
     * 列出用户所有记忆（按时间倒序）。
     */
    public List<UserMemoryEntry> listAll(String userId) {
        List<String> ids = redisTemplate.opsForList().range(String.format(KEY_IDS, userId), 0, -1);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<UserMemoryEntry> entries = new ArrayList<>();
        for (String id : ids) {
            String json = redisTemplate.opsForValue().get(String.format(KEY_ENTRY, userId, id));
            if (json != null) {
                try {
                    entries.add(objectMapper.readValue(json, UserMemoryEntry.class));
                } catch (JsonProcessingException e) {
                    log.warn("Failed to deserialize memory id={}", id, e);
                }
            }
        }
        entries.sort(Comparator.comparingLong(e -> -e.createdAt().toEpochMilli()));
        return entries;
    }

    /** 删除单条记忆。 */
    public void delete(String userId, String memoryId) {
        redisTemplate.opsForList().remove(String.format(KEY_IDS, userId), 1, memoryId);
        redisTemplate.delete(String.format(KEY_ENTRY, userId, memoryId));
        // 注：向量库中的对应向量暂不删除（需要通过 metadata 关联，简化处理）
        log.info("Deleted memory id={} userId={}", memoryId, userId);
    }

    private double decayedScore(double rawScore, Instant createdAt) {
        long days = Duration.between(createdAt, Instant.now()).toDays();
        if (days <= 0) return rawScore;
        double decay = Math.pow(0.5, days / DECAY_HALF_LIFE_DAYS);
        return rawScore * Math.max(decay, 1 - (days / (DECAY_HALF_LIFE_DAYS * 3)));
    }

    private double decayedScore(UserMemoryEntry e, Instant now) {
        long days = Duration.between(e.createdAt(), now).toDays();
        if (days <= 0) return 1.0;
        double decay = Math.pow(0.5, days / DECAY_HALF_LIFE_DAYS);
        return Math.max(decay, DECAY_MIN_SCORE);
    }

    private static String sha256(String input) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] h = d.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
