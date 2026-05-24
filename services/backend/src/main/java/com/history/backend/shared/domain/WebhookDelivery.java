package com.history.backend.shared.domain;

import java.time.Instant;
import java.util.UUID;

import com.history.backend.project.domain.Project;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

@Getter
@Entity
@Table(name = "webhook_deliveries")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookDelivery {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "delivery_id", nullable = false)
    private String deliveryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WebhookDeliveryStatus status;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_error")
    private String lastError;

    public static WebhookDelivery claimed(String deliveryId, Project project) {
        return new WebhookDelivery(deliveryId, project, WebhookDeliveryStatus.IN_PROGRESS, null);
    }

    private WebhookDelivery(
            String deliveryId,
            Project project,
            WebhookDeliveryStatus status,
            String lastError
    ) {
        this.deliveryId = deliveryId;
        this.project = project;
        this.status = status;
        this.lastError = lastError;
    }

    public void markProcessed() {
        this.status = WebhookDeliveryStatus.PROCESSED;
        this.lastError = null;
    }

    public void markFailed(String lastError) {
        this.status = WebhookDeliveryStatus.FAILED;
        this.lastError = lastError;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.receivedAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = WebhookDeliveryStatus.IN_PROGRESS;
        }
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
