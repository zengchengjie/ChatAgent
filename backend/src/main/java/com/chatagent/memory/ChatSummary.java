package com.chatagent.memory;

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
@Table(name = "chat_summaries")
public class ChatSummary {

    @Id
    @Column(name = "session_id", length = 36)
    private String sessionId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(name = "last_message_id", nullable = false)
    private Long lastMessageId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}

