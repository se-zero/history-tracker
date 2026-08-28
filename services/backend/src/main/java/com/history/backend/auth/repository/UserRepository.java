package com.history.backend.auth.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByIdAndDeletedAtIsNull(UUID id);

    // 프로젝트 생성 한도 검사와 insert를 같은 트랜잭션에서 직렬화하기 위한 사용자 행 잠금
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT user FROM User user WHERE user.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

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

    // 무료 질의 한도를 DB에서 검사·증가한다. 읽기-수정-쓰기면 동시 질의가 같은 값을 읽고 한도를 우회한다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE users
            SET free_query_count = free_query_count + 1,
                updated_at = now()
            WHERE id = :userId
              AND plan = 'FREE'
              AND free_query_count < :limit
            """, nativeQuery = true)
    int incrementFreeQueryCountIfBelowLimit(@Param("userId") UUID userId, @Param("limit") int limit);
}
