package com.history.backend.github.repository;

import java.util.Optional;
import java.util.UUID;

import com.history.backend.github.domain.GitHubInstallation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GitHubInstallationRepository extends JpaRepository<GitHubInstallation, UUID> {

    Optional<GitHubInstallation> findByInstallationId(Long installationId);
}
