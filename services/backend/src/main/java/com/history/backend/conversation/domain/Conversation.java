package com.history.backend.conversation.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.project.domain.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "conversations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Conversation {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column
    private String title;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "running_summary")
    private Map<String, Object> runningSummary;

    @Column(name = "summary_through_message_id")
    private UUID summaryThroughMessageId;

    @Column(name = "summary_updated_at")
    private Instant summaryUpdatedAt;

    @Column(name = "summary_version", nullable = false)
    private long summaryVersion;

    public Conversation(Project project, User user, String title) {
        this.project = project;
        this.user = user;
        this.title = title;
    }

    public boolean belongsToProject(UUID projectId) {
        return project != null && project.getId().equals(projectId);
    }

    public void updateTitle(String title) {
        this.title = title;
        touch();
    }

    // 대화 목록 정렬 기준인 updatedAt 갱신
    public void touch() {
        this.updatedAt = Instant.now();
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }
}
