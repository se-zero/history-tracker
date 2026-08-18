package com.history.backend.github.service;

import java.time.Instant;

public record InstallationAccessToken(
        String token,
        Instant expiresAt
) {
}
