package com.history.backend.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.repository.IntegrationRepository;
import com.history.backend.project.domain.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

// ClickUp access token은 만료·갱신·폐기가 없다(공식 문서 확정) — AsanaTokenService/LinearTokenService와
// 달리 캐시 재사용·비관적 잠금·REQUIRES_NEW 트랜잭션 기계가 전혀 필요 없는 단순 조회 accessor다.
@ExtendWith(MockitoExtension.class)
@DisplayName("ClickUpTokenService: ClickUp access token 조회")
class ClickUpTokenServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    @Mock
    private IntegrationRepository integrationRepository;

    @Mock
    private ClickUpCredentialCodec clickUpCredentialCodec;

    // MockitoExtension은 테스트 인스턴스 생성 후에 @Mock을 주입하므로 SUT를 필드 초기화식으로
    // 만들면 null mock을 캡처한다 — AsanaTokenServiceTest처럼 각 테스트에서 생성한다
    private ClickUpTokenService tokenService() {
        return new ClickUpTokenService(integrationRepository, clickUpCredentialCodec);
    }

    @Test
    @DisplayName("확정된 연동의 저장된 자격증명을 복호화해 access token 반환")
    void getAccessTokenReturnsDecryptedTokenForConfirmedIntegration() {
        Integration integration = clickUpIntegration(new byte[] {1, 2, 3});
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.CLICKUP))
                .thenReturn(Optional.of(integration));
        when(clickUpCredentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new ClickUpCredential("clickup-access-token"));

        String result = tokenService().getAccessToken(PROJECT_ID);

        assertThat(result).isEqualTo("clickup-access-token");
    }

    @Test
    @DisplayName("자격증명이 없는 연동은 UnauthorizedException 발생")
    void getAccessTokenRejectsIntegrationWithoutCredential() {
        Integration integration = clickUpIntegration(null);
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.CLICKUP))
                .thenReturn(Optional.of(integration));

        assertThatThrownBy(() -> tokenService().getAccessToken(PROJECT_ID))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("선택 단계 확정 전(pending) 연동에서도 토큰을 반환한다 — 선택 옵션 조회가 pending 중에도 호출된다")
    void getAccessTokenReturnsTokenEvenWhenPendingSelection() {
        Integration integration = Integration.pendingSelection(project(), IntegrationProvider.CLICKUP, new byte[] {1, 2, 3});
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.CLICKUP))
                .thenReturn(Optional.of(integration));
        when(clickUpCredentialCodec.decrypt(integration.getEncryptedCredential()))
                .thenReturn(new ClickUpCredential("clickup-access-token"));

        String result = tokenService().getAccessToken(PROJECT_ID);

        assertThat(result).isEqualTo("clickup-access-token");
        assertThat(integration.isPendingSelection()).isTrue();
    }

    private Integration clickUpIntegration(byte[] encryptedCredential) {
        return Integration.oauth(
                project(),
                IntegrationProvider.CLICKUP,
                Map.of("workspace_id", "workspace-1"),
                encryptedCredential);
    }

    private Project project() {
        User owner = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(owner, "id", UUID.randomUUID());
        Project project = new Project(owner, "History Tracker", null);
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);
        return project;
    }
}
