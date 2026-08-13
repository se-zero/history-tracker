package com.history.backend.integration.service;

import java.util.UUID;

import com.history.backend.common.error.NotFoundException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.repository.IntegrationRepository;
import org.springframework.stereotype.Service;

// ClickUp access token은 만료·갱신·폐기가 없다(공식 문서 확정) — AsanaTokenService/LinearTokenService와
// 달리 캐시 재사용·비관적 잠금·REQUIRES_NEW 트랜잭션 기계가 전혀 필요 없는 단순 조회 accessor다.
// 선택 단계 확정 전(pending) 상태에서도 호출된다 — 선택 옵션 조회가 그 상태에서 이뤄지기 때문에
// pending 여부로 막지 않는다.
@Service
public class ClickUpTokenService {

    private final IntegrationRepository integrationRepository;
    private final ClickUpCredentialCodec clickUpCredentialCodec;

    public ClickUpTokenService(
            IntegrationRepository integrationRepository,
            ClickUpCredentialCodec clickUpCredentialCodec
    ) {
        this.integrationRepository = integrationRepository;
        this.clickUpCredentialCodec = clickUpCredentialCodec;
    }

    public String getAccessToken(UUID projectId) {
        Integration integration = integrationRepository.findByProject_IdAndProvider(projectId, IntegrationProvider.CLICKUP)
                .orElseThrow(() -> new NotFoundException("ClickUp integration not found."));
        if (integration.getEncryptedCredential() == null) {
            throw new UnauthorizedException("ClickUp integration has no credential.");
        }
        return clickUpCredentialCodec.decrypt(integration.getEncryptedCredential()).accessToken();
    }
}
