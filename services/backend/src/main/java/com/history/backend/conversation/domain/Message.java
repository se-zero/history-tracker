package com.history.backend.conversation.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "messages")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Message {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageRole role;

    @Column(nullable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static Message user(Conversation conversation, String content) {
        return new Message(conversation, MessageRole.USER, content, null);
    }

    public static Message assistant(Conversation conversation, String content, Map<String, Object> metadata) {
        return new Message(conversation, MessageRole.ASSISTANT, content, metadata);
    }

    public static Message system(Conversation conversation, String content, Map<String, Object> metadata) {
        return new Message(conversation, MessageRole.SYSTEM, content, metadata);
    }

    private Message(
            Conversation conversation,
            MessageRole role,
            String content,
            Map<String, Object> metadata
    ) {
        this.conversation = conversation;
        this.role = role;
        this.content = content;
        this.metadata = metadata == null ? null : Map.copyOf(metadata);
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }
}
