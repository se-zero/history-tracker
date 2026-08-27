package com.history.backend.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.dto.UserResponse;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.github.dto.GitHubUserResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final String GITHUB_PROVIDER = "github";
    private static final Duration RESTORE_GRACE_PERIOD = Duration.ofDays(30);

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final String currentTermsVersion;

    // @Value는 필드가 아니라 생성자 파라미터에 붙여야 한다 — Lombok @RequiredArgsConstructor가
    // 생성한 생성자는 필드 애노테이션을 파라미터로 복사하지 않아 Spring이 "String" 타입 빈을
    // 찾다 실패한다(PipelineWorkerClient·InternalServiceAuthenticationFilter와 같은 패턴).
    public UserService(
            UserRepository userRepository,
            RefreshTokenService refreshTokenService,
            @Value("${app.legal.terms-version}") String currentTermsVersion
    ) {
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
        this.currentTermsVersion = currentTermsVersion;
    }

    // GitHub OAuth 로그인 시, 사용자 정보로 회원 가입 또는 기존 회원 정보 업데이트
    @Transactional
    public User upsertGitHubUser(GitHubUserResponse gitHubUser) {
        String providerUserId = String.valueOf(gitHubUser.id());
        return userRepository.findByProviderAndProviderUserIdAndDeletedAtIsNull(GITHUB_PROVIDER, providerUserId)
                .map(user -> updateProfile(user, gitHubUser))
                .orElseGet(() -> restoreOrCreate(providerUserId, gitHubUser));
    }

    // grace period 내 탈퇴 사용자 복구 또는 신규 생성
    private User restoreOrCreate(String providerUserId, GitHubUserResponse gitHubUser) {
        return userRepository.findFirstByProviderAndProviderUserIdOrderByCreatedAtDesc(GITHUB_PROVIDER, providerUserId)
                .filter(this::canRestore)
                .map(user -> {
                    user.restore();
                    return updateProfile(user, gitHubUser);
                })
                .orElseGet(() -> createUser(providerUserId, gitHubUser));
    }

    // 동시 가입 경합에 안전한 신규 사용자 생성
    private User createUser(String providerUserId, GitHubUserResponse gitHubUser) {
        return userRepository.insertActiveUserIfAbsent(
                        GITHUB_PROVIDER,
                        providerUserId,
                        gitHubUser.emailOrFallback(),
                        gitHubUser.displayName(),
                        gitHubUser.avatarUrl()
                )
                .flatMap(userRepository::findById)
                // 경합으로 insert가 무시된 경우 먼저 생성된 active user를 재조회해 사용
                .or(() -> userRepository.findByProviderAndProviderUserIdAndDeletedAtIsNull(
                        GITHUB_PROVIDER,
                        providerUserId
                ))
                .map(user -> updateProfile(user, gitHubUser))
                .orElseThrow(() -> new IllegalStateException("Failed to create or load GitHub user."));
    }

    private User updateProfile(User user, GitHubUserResponse gitHubUser) {
        user.updateProfile(gitHubUser.emailOrFallback(), gitHubUser.displayName(), gitHubUser.avatarUrl());
        return user;
    }

    // grace period(30일) 내 복구 가능 여부 판단
    private boolean canRestore(User user) {
        return user.getDeletedAt() != null
                && user.getDeletedAt().isAfter(Instant.now().minus(RESTORE_GRACE_PERIOD));
    }

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(UUID userId) {
        return UserResponse.from(getActiveUser(userId), currentTermsVersion);
    }

    // 사용자 soft delete 및 전체 refresh token 폐기
    @Transactional
    public void deactivateUser(UUID userId) {
        User user = getActiveUser(userId);
        user.softDelete(Instant.now());
        refreshTokenService.revokeAllRefreshTokens(user);
    }

    // 현재 약관 버전에 대한 동의 기록
    @Transactional
    public void recordConsent(UUID userId) {
        User user = getActiveUser(userId);
        user.recordConsent(currentTermsVersion, Instant.now());
    }

    // 활성(미탈퇴) 사용자 조회 — 비공개 API의 공통 사용자 검증 진입점
    @Transactional(readOnly = true)
    public User getActiveUser(UUID userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new NotFoundException("User not found."));
    }
}
