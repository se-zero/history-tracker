package com.history.backend.github.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.github.domain.GitHubInstallation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GitHubInstallationRepository extends JpaRepository<GitHubInstallation, UUID> {

    Optional<GitHubInstallation> findByInstallationId(Long installationId);

    List<GitHubInstallation> findAllByInstallerUser_Id(UUID installerId);

    Optional<GitHubInstallation> findByIdAndInstallerUser_Id(UUID id, UUID installerId);

    // 잠금·엔티티 로딩 없는 토큰 캐시 확인용 projection 조회
    @Query("""
            SELECT installation.encryptedInstallationToken AS encryptedInstallationToken,
                   installation.installationTokenExpiresAt AS installationTokenExpiresAt
            FROM GitHubInstallation installation
            WHERE installation.id = :id
            """)
    Optional<InstallationTokenCacheView> findTokenCacheById(@Param("id") UUID id);

    // 토큰 갱신 경합(중복 발급) 방지를 위한 비관적 잠금 조회
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT installation FROM GitHubInstallation installation WHERE installation.id = :id")
    Optional<GitHubInstallation> findByIdForUpdate(@Param("id") UUID id);

    // 동시 설치 경합 대비 ON CONFLICT DO NOTHING insert (생성된 경우에만 id 반환)
    @Query(value = """
            INSERT INTO github_installations (installation_id, account_type, account_login, installer_user_id)
            VALUES (:installationId, :accountType, :accountLogin, :installerUserId)
            ON CONFLICT (installation_id) DO NOTHING
            RETURNING id
            """, nativeQuery = true)
    Optional<UUID> insertInstallationIfAbsent(
            @Param("installationId") Long installationId,
            @Param("accountType") String accountType,
            @Param("accountLogin") String accountLogin,
            @Param("installerUserId") UUID installerUserId
    );
}
