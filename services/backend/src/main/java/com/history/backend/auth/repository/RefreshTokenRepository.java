package com.history.backend.auth.repository;

import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(byte[] tokenHash);

    void deleteByUserId(UUID userId);
}
