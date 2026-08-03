package com.history.backend.integration.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IntegrationRepository extends JpaRepository<Integration, UUID> {

    List<Integration> findAllByProject_IdOrderByCreatedAtDesc(UUID projectId);

    Optional<Integration> findByProject_IdAndProvider(UUID projectId, IntegrationProvider provider);

    boolean existsByProject_IdAndProvider(UUID projectId, IntegrationProvider provider);

    List<Integration> findAllByProvider(IntegrationProvider provider);

    // Jira 토큰 갱신 경합(동시 refresh로 인한 refresh token 상호 무효화) 방지를 위한 비관적 잠금 조회
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT integration FROM Integration integration "
            + "WHERE integration.project.id = :projectId AND integration.provider = :provider")
    Optional<Integration> findByProjectAndProviderForUpdate(
            @Param("projectId") UUID projectId,
            @Param("provider") IntegrationProvider provider
    );
}
