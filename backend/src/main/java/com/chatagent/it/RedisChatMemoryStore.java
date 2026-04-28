package com.chatagent.it;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "it-support:chat-memory:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        try {
            String raw = redisTemplate.opsForValue().get(KEY_PREFIX + memoryId);
            if (raw == null || raw.isBlank()) {
                return new ArrayList<>();
            }
            List<StoredChatMessage> stored = objectMapper.readValue(raw, new TypeReference<>() {});
            List<ChatMessage> result = new ArrayList<>(stored.size());
            for (StoredChatMessage m : stored) {
                if (m == null || m.type == null) {
                    continue;
                }
                String text = (m.text == null) ? "" : m.text;
                switch (m.type) {
                    case SYSTEM -> result.add(dev.langchain4j.data.message.SystemMessage.from(text));
                    case USER -> result.add(dev.langchain4j.data.message.UserMessage.from(text));
                    case AI -> result.add(AiMessage.from(text));
                    default -> {
                        // Skip unsupported message types for now (tool execution results, etc.)
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to load chat memory for memoryId={}", memoryId, e);
            return new ArrayList<>();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        try {
            List<StoredChatMessage> stored = new ArrayList<>(messages == null ? 0 : messages.size());
            if (messages != null) {
                for (ChatMessage msg : messages) {
                    if (msg == null) {
                        continue;
                    }
                    stored.add(StoredChatMessage.from(msg));
                }
            }
            redisTemplate.opsForValue().set(KEY_PREFIX + memoryId, objectMapper.writeValueAsString(stored));
        } catch (Exception e) {
            log.warn("Failed to persist chat memory for memoryId={}", memoryId, e);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(KEY_PREFIX + memoryId);
    }

    static final class StoredChatMessage {
        public dev.langchain4j.data.message.ChatMessageType type;
        public String text;

        public StoredChatMessage() {}

        StoredChatMessage(dev.langchain4j.data.message.ChatMessageType type, String text) {
            this.type = type;
            this.text = text;
        }

        static StoredChatMessage from(ChatMessage msg) {
            dev.langchain4j.data.message.ChatMessageType type = msg.type();
            String text = switch (type) {
                case SYSTEM -> ((dev.langchain4j.data.message.SystemMessage) msg).text();
                case USER -> ((dev.langchain4j.data.message.UserMessage) msg).text();
                case AI -> ((AiMessage) msg).text();
                default -> null;
            };
            return new StoredChatMessage(type, text);
        }
    }
}
