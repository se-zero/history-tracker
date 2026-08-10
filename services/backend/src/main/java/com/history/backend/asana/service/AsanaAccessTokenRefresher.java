package com.history.backend.asana.service;

import java.util.UUID;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.AccessTokenRefresher;
import com.history.backend.integration.service.AsanaTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AsanaAccessTokenRefresher implements AccessTokenRefresher {

    private final AsanaTokenService asanaTokenService;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.ASANA;
    }

    @Override
    public void ensureFreshAccessToken(UUID projectId) {
        asanaTokenService.ensureAccessToken(projectId);
    }
}
