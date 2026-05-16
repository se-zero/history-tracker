package com.history.backend.auth.service;

import com.history.backend.auth.domain.User;

public record RefreshTokenIssue(
        User user,
        String refreshToken
) {
}
