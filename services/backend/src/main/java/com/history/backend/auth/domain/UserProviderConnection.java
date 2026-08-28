package com.history.backend.auth.domain;

import java.time.Instant;
import java.util.UUID;

import com.history.backend.integration.domain.IntegrationProvider;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 무료 티어 provider 연동 이력 — (user, provider) 복합키. 해제 후에도 남겨 재연동을 영구히 막는다.
@Getter
@Entity
@Table(name = "user_provider_connections")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserProviderConnection {

    @EmbeddedId
    private UserProviderConnectionId id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "first_connected_at", nullable = false)
    private Instant firstConnectedAt;

    // userId를 user와 별도로 받는 이유: user는 저장 전 존재 조회 없이 넘기는 참조(getReferenceById)라
    // id 파생에 user.getId() 접근을 강제하면(프록시 초기화 자체는 안전하지만) 참조가 비어 있는
    // 호출부까지 다 같이 깨진다 — id는 항상 이 파라미터만으로 결정한다.
    public UserProviderConnection(User user, UUID userId, IntegrationProvider provider) {
        this.user = user;
        this.id = new UserProviderConnectionId(userId, provider);
    }

    public IntegrationProvider getProvider() {
        return id.getProvider();
    }

    @PrePersist
    void prePersist() {
        this.firstConnectedAt = Instant.now();
    }
}
