package com.history.backend.integration.service;

import java.util.List;
import java.util.UUID;

import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.repository.IntegrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// IntegrationService.disconnect의 private revokeProviderAccess를 leaf 서비스로 추출한 것 —
// ProjectService(프로젝트 삭제 시 전체 연동 일괄 폐기)와 IntegrationService(해제 시 단건 폐기)가
// 공유한다. ProjectService가 IntegrationService를 직접 부르면 순환 의존(IntegrationService →
// ProjectService)이 생기므로 둘 다 참조 가능한 leaf로 둔다.
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationRevocationService {

    private final IntegrationRepository integrationRepository;
    private final ProviderCredentialLifecycleRegistry credentialLifecycles;

    // 프로젝트의 모든 연동 권한을 일괄 폐기한다. 한 provider의 폐기 실패가 나머지 연동의 폐기를
    // 막으면 안 되므로(프로젝트 삭제 시 일부만 폐기되고 나머지 grant가 영구히 남는 것을 방지) 건별로
    // 예외를 삼키고 로그만 남긴다.
    public void revokeAll(UUID projectId) {
        List<Integration> integrations = integrationRepository.findAllByProject_IdOrderByCreatedAtDesc(projectId);
        for (Integration integration : integrations) {
            try {
                revoke(integration);
            } catch (RuntimeException exception) {
                log.warn("Failed to revoke provider access. provider={}, error={}",
                        integration.getProvider().value(), exception.getMessage());
            }
        }
    }

    // 폐기 방법은 provider의 ProviderCredentialLifecycle이 소유한다. GitHub은 폐기 대상이 없어
    // 구현이 없다 — App 설치는 계정 단위(다른 프로젝트도 쓴다)라 유지하고, installation token은
    // 1시간짜리 캐시라 방치해도 곧 만료된다. 제거는 GitHub 설정에서 한다.
    // registry에 등록된 provider는 항상 암호화된 credential을 갖고 있으므로(등록되지 않은 GitHub만
    // credential이 없다) find(provider)로만 걸러도 안전하다.
    public void revoke(Integration integration) {
        IntegrationProvider provider = integration.getProvider();
        credentialLifecycles.find(provider)
                .ifPresent(lifecycle -> lifecycle.revoke(
                        integration.getEncryptedCredential(), integration.getExternalRef()));
    }
}
