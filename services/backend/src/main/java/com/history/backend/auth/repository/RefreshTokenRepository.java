package com.history.backend.auth.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    // 동시 rotation에 의한 토큰 재사용 방지를 위한 비관적 잠금
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(byte[] tokenHash);

    void deleteByUserId(UUID userId);

    long deleteByExpiresAtBefore(Instant expiresAt);
}
