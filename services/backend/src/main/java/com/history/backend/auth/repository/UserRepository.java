package com.history.backend.auth.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByIdAndDeletedAtIsNull(UUID id);

    Optional<User> findByProviderAndProviderUserIdAndDeletedAtIsNull(String provider, String providerUserId);

    Optional<User> findFirstByProviderAndProviderUserIdOrderByCreatedAtDesc(String provider, String providerUserId);

    // purge 대상(탈퇴 후 cutoff 경과) 사용자 ID 페이지 조회. excludedIds는 같은 실행(cron 1회차)
    // 동안 자원 정리에 실패한 id — 이 메서드는 항상 page 0을 조회하므로 배제하지 않으면 선두
    // 실패 후보가 그 뒤 후보를 영원히 가린다. 빈 컬렉션이면 Hibernate가 NOT IN을 항상-참으로
    // 치환해 아무것도 배제하지 않는다.
    @Query("""
            SELECT user.id
            FROM User user
            WHERE user.deletedAt < :cutoff
              AND user.id NOT IN :excludedIds
            ORDER BY user.deletedAt ASC
            """)
    List<UUID> findPurgeCandidateIds(
            @Param("cutoff") Instant cutoff,
            @Param("excludedIds") Collection<UUID> excludedIds,
            Pageable pageable
    );

    // 동시 가입 경합 대비 ON CONFLICT DO NOTHING insert (생성된 경우에만 id 반환)
    @Query(value = """
            INSERT INTO users (provider, provider_user_id, email, display_name, avatar_url)
            VALUES (:provider, :providerUserId, :email, :displayName, :avatarUrl)
            ON CONFLICT (provider, provider_user_id) WHERE deleted_at IS NULL DO NOTHING
            RETURNING id
            """, nativeQuery = true)
    Optional<UUID> insertActiveUserIfAbsent(
            @Param("provider") String provider,
            @Param("providerUserId") String providerUserId,
            @Param("email") String email,
            @Param("displayName") String displayName,
            @Param("avatarUrl") String avatarUrl
    );
}
