package com.history.backend.github.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.github.domain.GitHubInstallation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GitHubInstallationRepository extends JpaRepository<GitHubInstallation, UUID> {

    Optional<GitHubInstallation> findByInstallationId(Long installationId);

    List<GitHubInstallation> findAllByInstallerUser_Id(UUID installerId);

    Optional<GitHubInstallation> findByIdAndInstallerUser_Id(UUID id, UUID installerId);

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
