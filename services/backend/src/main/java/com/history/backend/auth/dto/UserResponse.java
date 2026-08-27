package com.history.backend.auth.dto;

import java.util.UUID;

import com.history.backend.auth.domain.User;

public record UserResponse(
        UUID id,
        String provider,
        String providerUserId,
        String email,
        String displayName,
        String avatarUrl,
        boolean requiresConsent
) {

    public static UserResponse from(User user, String currentTermsVersion) {
        boolean requiresConsent = user.getConsentTermsVersion() == null
                || !user.getConsentTermsVersion().equals(currentTermsVersion);
        return new UserResponse(
                user.getId(),
                user.getProvider(),
                user.getProviderUserId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                requiresConsent
        );
    }
}
