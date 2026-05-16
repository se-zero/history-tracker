package com.history.backend.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.dto.UserResponse;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.github.dto.GitHubUserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final String GITHUB_PROVIDER = "github";
    private static final Duration RESTORE_GRACE_PERIOD = Duration.ofDays(30);

    private final UserRepository userRepository;

    // GitHub OAuth 로그인 시, 사용자 정보로 회원 가입 또는 기존 회원 정보 업데이트
    @Transactional
    public User upsertGitHubUser(GitHubUserResponse gitHubUser) {
        String providerUserId = String.valueOf(gitHubUser.id());
        return userRepository.findByProviderAndProviderUserIdAndDeletedAtIsNull(GITHUB_PROVIDER, providerUserId)
                .map(user -> updateProfile(user, gitHubUser))
                .orElseGet(() -> restoreOrCreate(providerUserId, gitHubUser));
    }

    private User restoreOrCreate(String providerUserId, GitHubUserResponse gitHubUser) {
        return userRepository.findFirstByProviderAndProviderUserIdOrderByCreatedAtDesc(GITHUB_PROVIDER, providerUserId)
                .filter(this::canRestore)
                .map(user -> {
                    user.restore();
                    return updateProfile(user, gitHubUser);
                })
                .orElseGet(() -> createUser(providerUserId, gitHubUser));
    }

    private User createUser(String providerUserId, GitHubUserResponse gitHubUser) {
        return userRepository.insertActiveUserIfAbsent(
                        GITHUB_PROVIDER,
                        providerUserId,
                        gitHubUser.emailOrFallback(),
                        gitHubUser.displayName(),
                        gitHubUser.avatarUrl()
                )
                .flatMap(userRepository::findById)
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

    private boolean canRestore(User user) {
        return user.getDeletedAt() != null
                && user.getDeletedAt().isAfter(Instant.now().minus(RESTORE_GRACE_PERIOD));
    }

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(UUID userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new NotFoundException("User not found."));
    }
}
