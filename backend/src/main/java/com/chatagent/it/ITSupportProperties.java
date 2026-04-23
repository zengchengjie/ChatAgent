package com.chatagent.it;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "it-support")
public class ITSupportProperties {

    private String chatModel = "qwen-turbo";
    private int maxMemoryMessages = 20;
    private int ragTopK = 3;
    private double semanticCacheSimilarityThreshold = 0.9;
    /**
     * Path to IT knowledge base markdown file.
     * Supports absolute paths, or paths relative to ${user.dir}.
     */
    private String knowledgeBasePath = "../docs/it-knowledge-base.md";
    /** 是否启用长期记忆功能 */
    private boolean memoryEnabled = true;
    /** 长期记忆检索返回条数 */
    private int memoryTopK = 5;
}
