package com.chatagent.it;

import com.chatagent.config.DashScopeProperties;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.service.AiServices;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class ITSupportAgentConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Bean
    EmbeddingModel itSupportEmbeddingModel(DashScopeProperties properties) {
        // Try different configurations for DashScope embedding
        // DashScope embedding might require different endpoint or parameters
        String baseUrl = properties.getBaseUrl();
        // If using compatible mode, embedding endpoint might be different
        if (baseUrl.contains("/compatible-mode/v1")) {
            baseUrl = baseUrl.replace("/compatible-mode/v1", "/v1");
        }

        return OpenAiEmbeddingModel.builder()
                .apiKey(properties.getApiKey())
                .baseUrl(baseUrl)
                .modelName("text-embedding-v2")
                .timeout(Duration.ofMillis(3000))
                .maxRetries(1)
                .customHeaders(Map.of(
                    "X-DashScope-Version", "2024-01-01",
                    "Authorization", "Bearer " + properties.getApiKey()
                ))
                .build();
    }

    @Bean
    @Primary
    RedisEmbeddingStore itSupportEmbeddingStore() {
        return RedisEmbeddingStore.builder()
                .host(redisHost)
                .port(redisPort)
                .password(redisPassword.isBlank() ? null : redisPassword)
                .indexName("it_support_kb")
                .dimension(1536) // text-embedding-v3
                .build();
    }

    /** 专门用于用户长期记忆的向量存储，使用独立索引 */
    @Bean
    RedisEmbeddingStore userMemoryEmbeddingStore() {
        return RedisEmbeddingStore.builder()
                .host(redisHost)
                .port(redisPort)
                .password(redisPassword.isBlank() ? null : redisPassword)
                .indexName("user_memory")
                .dimension(1536)
                .build();
    }

    @Bean
    OpenAiChatModel openAiChatModel(DashScopeProperties properties, ITSupportProperties itSupportProperties) {
        return OpenAiChatModel.builder()
                .apiKey(properties.getApiKey())
                .baseUrl(properties.getBaseUrl())
                .modelName(itSupportProperties.getChatModel())
                .temperature((double) properties.getTemperature())
                .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .maxRetries(1)
                .customHeaders(Map.of(
                    "X-DashScope-Version", "2024-01-01",
                    "Authorization", "Bearer " + properties.getApiKey()
                ))
                .build();
    }

    @Bean
    ITSupportAgent itSupportAgent(
            OpenAiChatModel chatModel,
            RedisChatMemoryStore memoryStore,
            ITSupportProperties itSupportProperties,
            ITSupportTools tools) {
        return AiServices.builder(ITSupportAgent.class)
                .chatLanguageModel(chatModel)
                .tools(tools)
                .chatMemoryProvider(memoryId -> dev.langchain4j.memory.chat.MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(itSupportProperties.getMaxMemoryMessages())
                        .chatMemoryStore(memoryStore)
                        .build())
                .build();
    }
}
