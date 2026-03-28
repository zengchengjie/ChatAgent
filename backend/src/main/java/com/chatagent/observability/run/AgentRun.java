package com.chatagent.observability.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "agent_runs")
public class AgentRun {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(nullable = false, length = 32)
    private String engine;

    @Column(length = 128)
    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "tokens_estimated", nullable = false)
    private boolean tokensEstimated;

    @Column(name = "cost_usd")
    private Double costUsd;

    @Column(name = "llm_calls", nullable = false)
    private int llmCalls;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "error_code", length = 64)
    private String errorCode;
}

