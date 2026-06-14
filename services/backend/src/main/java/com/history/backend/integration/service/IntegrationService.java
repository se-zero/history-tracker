package com.history.backend.integration.service;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.UUID;

import com.history.backend.common.crypto.CredentialCryptoService;
import com.history.backend.common.error.ConflictException;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.service.GitHubInstallationService;
import com.history.backend.github.service.InstallationTokenService;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.repository.IntegrationRepository;
import com.history.backend.jira.service.JiraClient;
import com.history.backend.project.domain.Project;
import com.history.backend.project.service.ProjectService;
import com.history.backend.slack.service.SlackClient;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class IntegrationService {

    private final IntegrationRepository integrationRepository;
    private final ProjectService projectService;
    private final GitHubInstallationService gitHubInstallationService;
    private final InstallationTokenService installationTokenService;
    private final CredentialCryptoService credentialCryptoService;
    private final SlackClient slackClient;
    private final JiraClient jiraClient;
    private final PipelineWorkerClient pipelineWorkerClient;
    private final PlatformTransactionManager transactionManager;

    // 프로젝트에 연동된 integration 목록 조회
    public List<Integration> listIntegrations(UUID ownerId, UUID projectId) {
        projectService.getProject(ownerId, projectId);
        return integrationRepository.findAllByProject_IdOrderByCreatedAtDesc(projectId);
    }

    // 프로젝트의 integration 연동 해제
    @Transactional
    public void disconnectIntegration(UUID ownerId, UUID projectId, UUID integrationId) {
        projectService.getProject(ownerId, projectId);
        Integration integration = integrationRepository.findByIdAndProject_Id(integrationId, projectId)
                .orElseThrow(() -> new NotFoundException("Integration not found."));
        integrationRepository.delete(integration);
    }

    // 프로젝트에 GitHub 저장소 연동 추가
    public Integration connectGitHubRepository(
            UUID ownerId,
            UUID projectId,
            UUID installationId,
            Long repositoryId,
            String repositoryFullName
    ) {
        Project project = projectService.getProject(ownerId, projectId);
        GitHubInstallation installation = gitHubInstallationService.getInstallationForInstaller(ownerId, installationId);
        validateProviderAvailable(projectId, IntegrationProvider.GITHUB);
        installationTokenService.getInstallationAccessToken(installationId);

        String normalizedRepositoryFullName = repositoryFullName.trim();
        // 토큰 발급 중 DB 커넥션·행 락 점유를 늘리지 않도록 연동 저장만 별도 트랜잭션으로 실행
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        Integration integration = transactionTemplate.execute(status -> saveGitHubRepository(
                project,
                installation,
                projectId,
                repositoryId,
                normalizedRepositoryFullName
        ));
        pipelineWorkerClient.triggerGitHubCollection(projectId);
        return integration;
    }

    private Integration saveGitHubRepository(
            Project project,
            GitHubInstallation installation,
            UUID projectId,
            Long repositoryId,
            String repositoryFullName
    ) {
        validateProviderAvailable(projectId, IntegrationProvider.GITHUB);
        try {
            return integrationRepository.saveAndFlush(Integration.github(
                    project,
                    installation,
                    repositoryId,
                    repositoryFullName
            ));
        } catch (DataIntegrityViolationException exception) {
            // 동시 연결 경합으로 사전 중복 검사를 통과한 경우 unique 제약 위반을 409로 변환
            throw new ConflictException("GitHub integration already exists.");
        }
    }

    // Slack 토큰 검증 후 workspace 연동 추가
    public Integration connectSlackWorkspace(
            UUID ownerId,
            UUID projectId,
            String token
    ) {
        String normalizedToken = token.trim();
        SlackClient.SlackWorkspace workspace = slackClient.verifyToken(normalizedToken);
        byte[] encryptedCredential = credentialCryptoService.encrypt(normalizedToken);

        // 외부 API 호출 중 DB 커넥션 점유를 피하기 위해 저장만 트랜잭션으로 분리
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        Integration integration = transactionTemplate.execute(status -> saveSlackWorkspace(
                ownerId,
                projectId,
                workspace,
                encryptedCredential
        ));
        pipelineWorkerClient.triggerSlackCollection(projectId);
        return integration;
    }

    // Jira 자격증명·프로젝트 검증 후 연동 추가
    public Integration connectJiraProject(
            UUID ownerId,
            UUID projectId,
            String baseUrl,
            String projectKey,
            String email,
            String apiToken
    ) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        String normalizedProjectKey = projectKey.trim();
        String normalizedEmail = email.trim();
        String normalizedApiToken = apiToken.trim();

        JiraClient.JiraProject jiraProject = jiraClient.verifyProject(
                normalizedBaseUrl,
                normalizedProjectKey,
                normalizedEmail,
                normalizedApiToken
        );
        byte[] encryptedCredential = credentialCryptoService.encrypt(
                normalizedEmail + ":" + normalizedApiToken
        );

        // 외부 API 호출 중 DB 커넥션 점유를 피하기 위해 저장만 트랜잭션으로 분리
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        Integration integration = transactionTemplate.execute(status -> saveJiraProject(
                ownerId,
                projectId,
                normalizedBaseUrl,
                jiraProject,
                encryptedCredential
        ));
        pipelineWorkerClient.triggerJiraCollection(projectId);
        return integration;
    }

    private Integration saveSlackWorkspace(
            UUID ownerId,
            UUID projectId,
            SlackClient.SlackWorkspace workspace,
            byte[] encryptedCredential
    ) {
        Project project = projectService.getProject(ownerId, projectId);
        validateProviderAvailable(projectId, IntegrationProvider.SLACK);

        try {
            return integrationRepository.saveAndFlush(Integration.slack(
                    project,
                    workspace.id(),
                    workspace.name(),
                    encryptedCredential
            ));
        } catch (DataIntegrityViolationException exception) {
            // 동시 연결 경합 시 unique 제약 위반을 409로 변환
            throw new ConflictException("Slack integration already exists.");
        }
    }

    private Integration saveJiraProject(
            UUID ownerId,
            UUID projectId,
            String baseUrl,
            JiraClient.JiraProject jiraProject,
            byte[] encryptedCredential
    ) {
        Project project = projectService.getProject(ownerId, projectId);
        validateProviderAvailable(projectId, IntegrationProvider.JIRA);

        try {
            return integrationRepository.saveAndFlush(Integration.jira(
                    project,
                    jiraProject.key(),
                    jiraProject.name(),
                    baseUrl,
                    encryptedCredential
            ));
        } catch (DataIntegrityViolationException exception) {
            // 동시 연결 경합 시 unique 제약 위반을 409로 변환
            throw new ConflictException("Jira integration already exists.");
        }
    }

    // Jira base URL 정규화 및 검증 (https 필수, 공개 호스트만 허용)
    private String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            URI uri = new URI(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new BadRequestException("Jira base URL must start with https://.");
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new BadRequestException("Jira base URL host is required.");
            }
            validatePublicHost(host);
            return uri.toString();
        } catch (URISyntaxException exception) {
            throw new BadRequestException("Jira base URL is invalid.");
        }
    }

    // SSRF 방지 — 내부망·루프백 주소로 해석되는 호스트 차단
    private void validatePublicHost(String host) {
        String normalizedHost = host.toLowerCase();
        if ("localhost".equals(normalizedHost) || normalizedHost.endsWith(".localhost")) {
            throw new BadRequestException("Jira base URL host must be public.");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (!isPublicAddress(address)) {
                    throw new BadRequestException("Jira base URL host must be public.");
                }
            }
        } catch (UnknownHostException exception) {
            throw new BadRequestException("Jira base URL host is invalid.");
        }
    }

    private boolean isPublicAddress(InetAddress address) {
        return !address.isAnyLocalAddress()
                && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress()
                && !address.isSiteLocalAddress()
                && !isUniqueLocalIpv6Address(address);
    }

    // IPv6 ULA(fc00::/7) 여부 판별
    private boolean isUniqueLocalIpv6Address(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        byte firstByte = address.getAddress()[0];
        return (firstByte & 0xfe) == 0xfc;
    }

    // 프로젝트당 provider별 1개 연동 제한 검증
    private void validateProviderAvailable(UUID projectId, IntegrationProvider provider) {
        if (integrationRepository.existsByProject_IdAndProvider(projectId, provider)) {
            throw new ConflictException(providerDisplayName(provider) + " integration already exists.");
        }
    }

    private String providerDisplayName(IntegrationProvider provider) {
        return switch (provider) {
            case GITHUB -> "GitHub";
            case SLACK -> "Slack";
            case JIRA -> "Jira";
        };
    }
}
