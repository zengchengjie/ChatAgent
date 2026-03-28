package com.chatagent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AppProperties.class, DashScopeProperties.class, AgentProperties.class, LlmPricingProperties.class})
public class PropertiesConfig {}
