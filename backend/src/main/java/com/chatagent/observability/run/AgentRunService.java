package com.chatagent.observability.run;

import com.chatagent.config.LlmPricingProperties;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentRunService {

    private final AgentRunRepository repository;
    private final LlmPricingProperties pricingProperties;

    public AgentRun start(Long userId, String sessionId, String traceId, String engine, String model) {
        AgentRun run = new AgentRun();
        run.setId(UUID.randomUUID().toString());
        run.setTraceId(traceId);
        run.setUserId(userId);
        run.setSessionId(sessionId);
        run.setEngine(engine);
        run.setModel(model);
        run.setStartedAt(Instant.now());
        run.setLlmCalls(0);
        return repository.save(run);
    }

    public void finish(
            AgentRun run,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            boolean estimated,
            int llmCalls,
            String errorCode) {
        run.setPromptTokens(promptTokens);
        run.setCompletionTokens(completionTokens);
        run.setTotalTokens(totalTokens);
        run.setTokensEstimated(estimated);
        run.setLlmCalls(llmCalls);
        run.setErrorCode(errorCode);
        run.setCostUsd(computeCostUsd(run.getModel(), promptTokens, completionTokens));
        run.setEndedAt(Instant.now());
        repository.save(run);
    }

    public Double computeCostUsd(String model, Integer promptTokens, Integer completionTokens) {
        if (model == null || model.isBlank()) {
            return 0.0;
        }
        LlmPricingProperties.ModelPricing p = pricingProperties.getModels().get(model);
        if (p == null) {
            return 0.0;
        }
        double prompt = promptTokens == null ? 0 : promptTokens;
        double completion = completionTokens == null ? 0 : completionTokens;
        return prompt / 1000.0 * p.getPromptUsdPer1k() + completion / 1000.0 * p.getCompletionUsdPer1k();
    }
}

