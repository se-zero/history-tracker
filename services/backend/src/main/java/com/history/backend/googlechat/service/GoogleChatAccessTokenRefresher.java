package com.history.backend.googlechat.service;

import java.util.UUID;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.AccessTokenRefresher;
import com.history.backend.integration.service.GoogleChatTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// Google Chat access token은 1시간 만료라 갱신이 필요하다.
// 갱신 주체는 GoogleChatTokenService 하나다 — pipeline-worker는 내부 토큰 API로만 위임한다.
@Service
@RequiredArgsConstructor
public class GoogleChatAccessTokenRefresher implements AccessTokenRefresher {

    private final GoogleChatTokenService tokenService;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.GOOGLE_CHAT;
    }

    @Override
    public void ensureFreshAccessToken(UUID projectId) {
        tokenService.ensureAccessToken(projectId);
    }
}
