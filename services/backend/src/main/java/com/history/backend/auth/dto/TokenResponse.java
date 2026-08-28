package com.history.backend.auth.dto;

public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {
}
