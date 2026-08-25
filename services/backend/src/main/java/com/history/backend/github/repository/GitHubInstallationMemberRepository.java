package com.history.backend.github.repository;

import java.util.UUID;

import com.history.backend.github.domain.GitHubInstallationMember;
import com.history.backend.github.domain.GitHubInstallationMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GitHubInstallationMemberRepository
        extends JpaRepository<GitHubInstallationMember, GitHubInstallationMemberId> {

    // 로그인마다 호출되므로 멱등해야 한다 — 이미 등록된 멤버는 조용히 무시
    @Modifying
    @Query(value = """
            INSERT INTO github_installation_users (installation_id, user_id)
            VALUES (:installationId, :userId)
            ON CONFLICT (installation_id, user_id) DO NOTHING
            """, nativeQuery = true)
    void addMember(@Param("installationId") UUID installationId, @Param("userId") UUID userId);
}
