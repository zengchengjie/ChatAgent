package com.chatagent.config;

import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.llm-pricing")
public class LlmPricingProperties {

    /**
     * Model pricing config.
     *
     * <p>Key is model name, value is pricing in USD per 1K tokens.
     */
    private Map<String, ModelPricing> models = new HashMap<>();

    @Getter
    @Setter
    public static class ModelPricing {
        private double promptUsdPer1k = 0.0;
        private double completionUsdPer1k = 0.0;
    }
}

