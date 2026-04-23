package com.chatagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ollama")
public class OllamaProperties {

    private String baseUrl = "http://localhost:11434/v1";
    private String model = "deepseek-r1:1.5b";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 10000;
    private double temperature = 0.1;
    private int maxRetries = 0;
}