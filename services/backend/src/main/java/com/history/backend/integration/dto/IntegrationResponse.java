package com.history.backend.integration.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.history.backend.integration.domain.Integration;

public record IntegrationResponse(
        UUID id,
        UUID projectId,
        String provider,
        Map<String, Object> externalRef,
        UUID installationId,
        Instant createdAt,
        Instant updatedAt
) {

    public static IntegrationResponse from(Integration integration) {
        return new IntegrationResponse(
                integration.getId(),
                integration.getProject().getId(),
                integration.getProvider().value(),
                integration.getExternalRef(),
                integration.getInstallation() == null ? null : integration.getInstallation().getId(),
                integration.getCreatedAt(),
                integration.getUpdatedAt()
        );
    }
}
