package com.history.backend.integration.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationRepository extends JpaRepository<Integration, UUID> {

    List<Integration> findAllByProject_IdOrderByCreatedAtDesc(UUID projectId);

    Optional<Integration> findByProject_IdAndProvider(UUID projectId, IntegrationProvider provider);

    Optional<Integration> findByIdAndProject_Id(UUID integrationId, UUID projectId);

    boolean existsByProject_IdAndProvider(UUID projectId, IntegrationProvider provider);
}
