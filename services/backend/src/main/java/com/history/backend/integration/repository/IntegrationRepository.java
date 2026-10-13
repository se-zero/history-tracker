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

    // 유료 전환 시 소유 연동 전체에 증분 수집을 다시 켜기 위한 조회
    List<Integration> findAllByProject_Owner_Id(UUID ownerId);

    // 베타 정리 후보 — 목표값 = betaActive && 미탈퇴. 수집 서버는 요금제를 보지 않고 플래그만 읽으므로
    // 그 목표값과 다른 연동 행을 가진 FREE 소유자를 찾는다(PAID는 항상 켜져 있어 대상이 아니다).
    @Query("""
            SELECT DISTINCT o.id FROM Integration i JOIN i.project p JOIN p.owner o
            WHERE o.plan = com.history.backend.auth.domain.Plan.FREE
              AND ((:betaActive = true AND o.deletedAt IS NULL AND i.incrementalEnabled = false)
                OR ((:betaActive = false OR o.deletedAt IS NOT NULL) AND i.incrementalEnabled = true))
            """)
    List<UUID> findFreeOwnerIdsWithIncrementalDrift(@Param("betaActive") boolean betaActive);

    // Jira 토큰 갱신 경합(동시 refresh로 인한 refresh token 상호 무효화) 방지를 위한 비관적 잠금 조회
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT integration FROM Integration integration "
            + "WHERE integration.project.id = :projectId AND integration.provider = :provider")
    Optional<Integration> findByProjectAndProviderForUpdate(
            @Param("projectId") UUID projectId,
            @Param("provider") IntegrationProvider provider
    );
}
