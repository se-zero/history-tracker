package com.history.backend.github.repository;

import java.util.Collection;
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

    // 로그인 동기화에서 접근권을 잃은(kept에 없는) 설치의 멤버십만 지운다. keptInstallationIds가
    // 비어 있으면 Hibernate가 IN 술어를 항상-거짓으로 치환하므로 NOT IN은 항상-참이 되어 안전하게
    // 해당 사용자의 멤버십 전체가 삭제 대상이 된다.
    @Modifying
    @Query("""
            DELETE FROM GitHubInstallationMember member
            WHERE member.id.userId = :userId
              AND member.id.installationId NOT IN :keptInstallationIds
            """)
    void pruneMemberships(
            @Param("userId") UUID userId,
            @Param("keptInstallationIds") Collection<UUID> keptInstallationIds
    );
}
