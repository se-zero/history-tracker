package com.history.backend.linear.service;

import java.util.UUID;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.AccessTokenRefresher;
import com.history.backend.integration.service.LinearTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// Linear access token은 24시간 만료라 갱신이 필요하다.
// 회전하는 refresh token을 둘이 갱신하면 서로를 무효화하므로 갱신 주체는 LinearTokenService 하나다.
@Service
@RequiredArgsConstructor
public class LinearAccessTokenRefresher implements AccessTokenRefresher {

    private final LinearTokenService linearTokenService;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.LINEAR;
    }

    @Override
    public void ensureFreshAccessToken(UUID projectId) {
        linearTokenService.ensureAccessToken(projectId);
    }
}
