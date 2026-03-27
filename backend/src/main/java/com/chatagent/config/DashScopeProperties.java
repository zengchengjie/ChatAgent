package com.chatagent.config;

import lombok.Getter;
import lombok.Setter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "dashscope")
public class DashScopeProperties {

    private String apiKey = "";
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String model = "qwen-plus";
    /** Allowed model names for session-level selection (allowlist). */
    private List<String> allowedModels = new ArrayList<>();
    private int connectTimeoutMs = 10000;
    private int readTimeoutMs = 120000;
    private int maxTokens = 4096;
    private double temperature = 0.3;
}
