package com.history.backend.github.repository;

import java.util.Optional;
import java.util.UUID;

import com.history.backend.github.domain.GitHubUserCredentialEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GitHubUserCredentialRepository extends JpaRepository<GitHubUserCredentialEntity, UUID> {

    // 토큰 갱신 경합(중복 교환) 방지를 위한 비관적 잠금 조회
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT credential FROM GitHubUserCredentialEntity credential WHERE credential.userId = :userId")
    Optional<GitHubUserCredentialEntity> findByIdForUpdate(@Param("userId") UUID userId);
}
