package com.history.backend.integration.service;

import java.util.UUID;

// OAuthStateService.verify가 복원하는 state 클레임
public record OAuthStateClaims(
        UUID projectId,
        UUID userId,
        String provider
) {
}
