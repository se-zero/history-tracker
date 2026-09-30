package com.history.backend.oauth.dto;

import java.time.Instant;

public record OAuthGrantResponse(
        String id,
        String clientId,
        String clientName,
        String clientUri,
        Instant grantedAt,
        Instant lastUsedAt
) {
}
