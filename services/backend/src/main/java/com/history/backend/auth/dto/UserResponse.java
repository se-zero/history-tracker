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
        Integer freeQueryRemaining,
        Integer freeQueryLimit
) {

    // 버전이 전부 YYYY-MM-DD(시행일)라 사전순 비교가 곧 날짜순 비교다. 재동의 기준 이상의
    // 버전에 이미 동의한 사용자는 재동의를 요구하지 않는다(약관 개정 시 공지 + 계속 이용으로
    // 동의 간주하는 정책과, 기록 버전과 재동의 기준을 분리해 사소한 개정에 전원 재동의를
    // 강제하지 않기 위함이다).
    public static UserResponse from(User user, String consentRequiredSinceVersion) {
        boolean requiresConsent = user.getConsentTermsVersion() == null
                || user.getConsentTermsVersion().compareTo(consentRequiredSinceVersion) < 0;
        boolean paid = user.getPlan() == Plan.PAID;
        Integer freeQueryRemaining = paid
                ? null
                : Math.max(0, PlanService.FREE_QUERY_LIMIT - user.getFreeQueryCount());
        Integer freeQueryLimit = paid ? null : PlanService.FREE_QUERY_LIMIT;
        return new UserResponse(
                user.getId(),
                user.getProvider(),
                user.getProviderUserId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                requiresConsent,
                user.getPlan(),
                freeQueryRemaining,
                freeQueryLimit
        );
    }
}
