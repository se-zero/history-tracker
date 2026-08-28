package com.history.backend.auth.dto;

import java.util.UUID;

import com.history.backend.auth.domain.Plan;
import com.history.backend.auth.domain.User;
import com.history.backend.auth.service.PlanService;

public record UserResponse(
        UUID id,
        String provider,
        String providerUserId,
        String email,
        String displayName,
        String avatarUrl,
        boolean requiresConsent,
        Plan plan,
        Integer freeQueryRemaining
) {

    public static UserResponse from(User user, String currentTermsVersion) {
        boolean requiresConsent = user.getConsentTermsVersion() == null
                || !user.getConsentTermsVersion().equals(currentTermsVersion);
        Integer freeQueryRemaining = user.getPlan() == Plan.PAID
                ? null
                : Math.max(0, PlanService.FREE_QUERY_LIMIT - user.getFreeQueryCount());
        return new UserResponse(
                user.getId(),
                user.getProvider(),
                user.getProviderUserId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                requiresConsent,
                user.getPlan(),
                freeQueryRemaining
        );
    }
}
