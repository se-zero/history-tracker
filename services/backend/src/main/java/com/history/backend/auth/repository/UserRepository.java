package com.history.backend.auth.repository;

import java.time.Instant;
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

    @Query("""
            SELECT user.id
            FROM User user
            WHERE user.deletedAt < :cutoff
            ORDER BY user.deletedAt ASC
            """)
    List<UUID> findPurgeCandidateIds(
            @Param("cutoff") Instant cutoff,
            Pageable pageable
    );

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
