package com.chatagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final Cors cors = new Cors();
    private final RateLimit rateLimit = new RateLimit();
    private final Admin admin = new Admin();
    private final Agent agent = new Agent();

    @Getter
    @Setter
    public static class Jwt {
        private String secret;
        private long expirationMs = 86400000L;
    }

    @Getter
    @Setter
    public static class Cors {
        /** Comma-separated origins for browser clients */
        private String allowedOrigins = "http://localhost:5173,http://127.0.0.1:5173";
    }

    @Getter
    @Setter
    public static class RateLimit {
        private int agentRequestsPerMinute = 30;
    }

    @Getter
    @Setter
    public static class Admin {
        private String bootstrapUsername = "admin";
        private String bootstrapPassword = "admin";
    }

    @Getter
    @Setter
    public static class Agent {
        /** self | langchain4j */
        private String engine = "langchain4j";
    }
}
