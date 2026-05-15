package com.history.backend.auth.repository;

import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(byte[] tokenHash);

    void deleteByUserId(UUID userId);
}
