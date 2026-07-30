package com.history.backend.integration.service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.common.crypto.CredentialCryptoService;
import com.history.backend.common.error.ConflictException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.service.GitHubInstallationService;
import com.history.backend.github.service.InstallationTokenService;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.dto.IntegrationResponse;
import com.history.backend.integration.repository.IntegrationRepository;
import com.history.backend.jira.service.JiraClient;
import com.history.backend.jira.service.JiraOAuthClient;
import com.history.backend.project.domain.Project;
import com.history.backend.project.service.ProjectService;
import com.history.backend.shared.domain.Checkpoint;
import com.history.backend.shared.repository.CheckpointRepository;
import com.history.backend.slack.service.SlackClient;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class IntegrationService {

    private final IntegrationRepository integrationRepository;
    private final CheckpointRepository checkpointRepository;
    private final ProjectService projectService;
    private final GitHubInstallationService gitHubInstallationService;
    private final InstallationTokenService installationTokenService;
    private final CredentialCryptoService credentialCryptoService;
    private final SlackClient slackClient;
    private final JiraOAuthClient jiraOAuthClient;
    private final JiraClient jiraClient;
    private final JiraCredentialCodec jiraCredentialCodec;
    private final PipelineWorkerClient pipelineWorkerClient;
    private final TransactionTemplate transactionTemplate;

    // 프로젝트에 연동된 integration 목록 조회 (provider별 마지막 수집 시각 포함)
    public List<IntegrationResponse> listIntegrations(UUID ownerId, UUID projectId) {
        projectService.getProject(ownerId, projectId);
        Map<IntegrationProvider, Instant> lastSyncedByProvider = latestSyncByProvider(projectId);
        return integrationRepository.findAllByProject_IdOrderByCreatedAtDesc(projectId).stream()
                .map(integration -> IntegrationResponse.from(
                        integration,
                        lastSyncedByProvider.get(integration.getProvider())))
                .toList();
    }

    // checkpoint는 provider당 여러 cursor_key를 가지므로 provider별 최신 갱신 시각을 마지막 수집 시각으로 사용
    private Map<IntegrationProvider, Instant> latestSyncByProvider(UUID projectId) {
        Map<IntegrationProvider, Instant> latest = new EnumMap<>(IntegrationProvider.class);
        for (Checkpoint checkpoint : checkpointRepository.findAllByProject_Id(projectId)) {
            latest.merge(
                    checkpoint.getProvider(),
                    checkpoint.getUpdatedAt(),
                    (existing, candidate) -> candidate.isAfter(existing) ? candidate : existing);
        }
        return latest;
    }

    // 프로젝트에 GitHub 저장소 연동 추가
    public Integration connectGitHubRepository(
            UUID ownerId,
            UUID projectId,
            UUID installationId,
            Long repositoryId,
            String repositoryFullName,
            String branch
    ) {
        Project project = projectService.getProject(ownerId, projectId);
        GitHubInstallation installation = gitHubInstallationService.getInstallationForInstaller(ownerId, installationId);
        validateProviderAvailable(projectId, IntegrationProvider.GITHUB);
        installationTokenService.getInstallationAccessToken(installationId);

        String normalizedRepositoryFullName = repositoryFullName.trim();
        String normalizedBranch = branch.trim();
        // 토큰 발급 중 DB 커넥션·행 락 점유를 늘리지 않도록 연동 저장만 별도 트랜잭션으로 실행
        Integration integration = transactionTemplate.execute(status -> saveGitHubRepository(
                project,
                installation,
                projectId,
                repositoryId,
                normalizedRepositoryFullName,
                normalizedBranch
        ));
        pipelineWorkerClient.triggerCollection(IntegrationProvider.GITHUB, projectId);
        return integration;
    }

    private Integration saveGitHubRepository(
            Project project,
            GitHubInstallation installation,
            UUID projectId,
            Long repositoryId,
            String repositoryFullName,
            String branch
    ) {
        validateProviderAvailable(projectId, IntegrationProvider.GITHUB);
        try {
            return integrationRepository.saveAndFlush(Integration.github(
                    project,
                    installation,
                    repositoryId,
                    repositoryFullName,
                    branch
            ));
        } catch (DataIntegrityViolationException exception) {
            // 동시 연결 경합으로 사전 중복 검사를 통과한 경우 unique 제약 위반을 409로 변환
            throw integrationAlreadyExists(IntegrationProvider.GITHUB);
        }
    }

    // Slack code 교환 후 workspace 연동 추가
    public Integration connectSlackWorkspace(
            UUID ownerId,
            UUID projectId,
            String code
    ) {
        projectService.getProject(ownerId, projectId);
        // 이미 연동된 프로젝트라면 code 교환으로 낭비하지 않도록 외부 호출 전에 선검증
        validateProviderAvailable(projectId, IntegrationProvider.SLACK);
        SlackClient.SlackWorkspace workspace = slackClient.exchangeCode(code);
        byte[] encryptedCredential = credentialCryptoService.encrypt(workspace.accessToken());

        // 외부 API 호출 중 DB 커넥션 점유를 피하기 위해 저장만 트랜잭션으로 분리
        Integration integration = transactionTemplate.execute(status -> saveSlackWorkspace(
                ownerId,
                projectId,
                workspace,
                encryptedCredential
        ));
        pipelineWorkerClient.triggerCollection(IntegrationProvider.SLACK, projectId);
        return integration;
    }

    // Atlassian code 교환 후 Jira 연동을 pending 상태로 생성/재시도한다.
    // 확정된 연동이면 code 교환 전에 걸러 1회용 code를 낭비하지 않는다(Slack과 동일한 방침).
    public Integration connectJiraSite(UUID ownerId, UUID projectId, String code) {
        projectService.getProject(ownerId, projectId);
        rejectIfJiraAlreadyConnected(projectId);

        JiraOAuthClient.JiraTokens tokens = jiraOAuthClient.exchangeCode(code);
        JiraCredential credential = new JiraCredential(
                tokens.accessToken(),
                tokens.refreshToken(),
                Instant.now().plusSeconds(tokens.expiresIn())
        );
        byte[] encryptedCredential = jiraCredentialCodec.encrypt(credential);

        // 외부 API 호출 중 DB 커넥션 점유를 피하기 위해 저장만 트랜잭션으로 분리
        return transactionTemplate.execute(status -> saveJiraPending(ownerId, projectId, encryptedCredential));
    }

    // 저장된 pending 자격증명을 복호화해 접근 가능한 Atlassian 사이트 목록 조회
    public List<JiraOAuthClient.JiraSite> listJiraSites(UUID ownerId, UUID projectId) {
        projectService.getProject(ownerId, projectId);
        JiraCredential credential = jiraCredentialCodec.decrypt(getJiraIntegration(projectId).getEncryptedCredential());
        return jiraOAuthClient.listAccessibleResources(credential.accessToken());
    }

    // 선택한 사이트(cloudId)에서 고를 수 있는 프로젝트 목록 조회
    public List<JiraClient.JiraProject> listJiraProjects(UUID ownerId, UUID projectId, String cloudId) {
        projectService.getProject(ownerId, projectId);
        JiraCredential credential = jiraCredentialCodec.decrypt(getJiraIntegration(projectId).getEncryptedCredential());
        return jiraClient.listProjects(cloudId, credential.accessToken());
    }

    // 사이트·프로젝트 선택 확정. pending 행에만 허용하고, 커밋 뒤 초기 수집을 트리거한다.
    public Integration completeJiraProject(
            UUID ownerId,
            UUID projectId,
            String cloudId,
            String siteName,
            String projectKey,
            String projectName
    ) {
        projectService.getProject(ownerId, projectId);
        Integration integration = transactionTemplate.execute(status -> {
            Integration jiraIntegration = getJiraIntegration(projectId);
            if (!jiraIntegration.isJiraPendingProject()) {
                throw integrationAlreadyExists(IntegrationProvider.JIRA);
            }
            jiraIntegration.completeJiraProject(cloudId, siteName, projectKey, projectName);
            return integrationRepository.saveAndFlush(jiraIntegration);
        });
        pipelineWorkerClient.triggerCollection(IntegrationProvider.JIRA, projectId);
        return integration;
    }

    // 재시도는 pending 행에만 허용한다 — 확정된 연동에는 409로 code 교환 전에 막는다
    private void rejectIfJiraAlreadyConnected(UUID projectId) {
        integrationRepository.findByProject_IdAndProvider(projectId, IntegrationProvider.JIRA)
                .filter(integration -> !integration.isJiraPendingProject())
                .ifPresent(integration -> {
                    throw integrationAlreadyExists(IntegrationProvider.JIRA);
                });
    }

    private Integration getJiraIntegration(UUID projectId) {
        return integrationRepository.findByProject_IdAndProvider(projectId, IntegrationProvider.JIRA)
                .orElseThrow(() -> new NotFoundException("Jira integration not found."));
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
            throw integrationAlreadyExists(IntegrationProvider.SLACK);
        }
    }

    // pending 재시도(경합 방어를 위해 트랜잭션 안에서 재확인) 또는 신규 생성
    private Integration saveJiraPending(UUID ownerId, UUID projectId, byte[] encryptedCredential) {
        Project project = projectService.getProject(ownerId, projectId);
        Optional<Integration> existing = integrationRepository.findByProject_IdAndProvider(projectId, IntegrationProvider.JIRA);
        if (existing.isPresent()) {
            Integration integration = existing.get();
            if (!integration.isJiraPendingProject()) {
                // 사전 검사와 저장 사이 경합으로 그 사이 확정된 경우
                throw integrationAlreadyExists(IntegrationProvider.JIRA);
            }
            integration.updateCredential(encryptedCredential);
            return integrationRepository.saveAndFlush(integration);
        }
        try {
            return integrationRepository.saveAndFlush(Integration.jiraPending(project, encryptedCredential));
        } catch (DataIntegrityViolationException exception) {
            // 동시 연결 경합 시 unique 제약 위반을 409로 변환
            throw integrationAlreadyExists(IntegrationProvider.JIRA);
        }
    }

    // 프로젝트당 provider별 1개 연동 제한 검증
    private void validateProviderAvailable(UUID projectId, IntegrationProvider provider) {
        if (integrationRepository.existsByProject_IdAndProvider(projectId, provider)) {
            throw integrationAlreadyExists(provider);
        }
    }

    private ConflictException integrationAlreadyExists(IntegrationProvider provider) {
        return new ConflictException(provider.displayName() + " integration already exists.");
    }
}
