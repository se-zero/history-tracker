package com.history.backend.integration.service;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.service.PlanService;
import com.history.backend.common.crypto.CredentialCryptoService;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.ConflictException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.service.GitHubInstallationService;
import com.history.backend.github.service.InstallationTokenService;
import com.history.backend.graph.service.AiEngineGraphClient;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.domain.SelectionStep;
import com.history.backend.integration.dto.IntegrationResponse;
import com.history.backend.integration.repository.IntegrationRepository;
import com.history.backend.project.domain.Project;
import com.history.backend.project.service.ProjectService;
import com.history.backend.shared.domain.Checkpoint;
import com.history.backend.shared.repository.CheckpointRepository;
import com.history.backend.slack.service.SlackClient;
import com.history.backend.slack.service.SlackOAuthConnectFlow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationService {

    private final IntegrationRepository integrationRepository;
    private final CheckpointRepository checkpointRepository;
    private final ProjectService projectService;
    private final GitHubInstallationService gitHubInstallationService;
    private final InstallationTokenService installationTokenService;
    private final CredentialCryptoService credentialCryptoService;
    private final PipelineWorkerClient pipelineWorkerClient;
    private final AiEngineGraphClient aiEngineGraphClient;
    private final ProviderCredentialLifecycleRegistry credentialLifecycles;
    private final IntegrationRevocationService revocationService;
    private final AccessTokenRefresherRegistry accessTokenRefreshers;
    private final IntegrationSelectionFlowRegistry selectionFlows;
    private final TransactionTemplate transactionTemplate;
    private final PlanService planService;
    private final SlackClient slackClient;
    private final SlackCredentialCodec slackCredentialCodec;

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
        planService.ensureProviderConnectable(ownerId, IntegrationProvider.GITHUB);
        GitHubInstallation installation = gitHubInstallationService.getAccessibleInstallation(ownerId, installationId);
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
        planService.recordProviderConnected(ownerId, IntegrationProvider.GITHUB);
        pipelineWorkerClient.triggerCollection(IntegrationProvider.GITHUB, projectId);
        return integration;
    }

    // 프로젝트 생성과 GitHub 연동을 한 트랜잭션으로 처리 (온보딩 전용 경로)
    public Project createProjectWithGitHubRepository(
            UUID ownerId,
            String name,
            String description,
            UUID installationId,
            Long repositoryId,
            String repositoryFullName,
            String branch
    ) {
        // 설치 조회가 active user 검증을 겸한다. 외부 호출(토큰 발급)은 트랜잭션 시작 전에 끝내
        // 발급이 실패하면 프로젝트가 아예 만들어지지 않게 하고, 발급 지연 동안 DB 커넥션도 잡지 않는다.
        GitHubInstallation installation = gitHubInstallationService.getAccessibleInstallation(ownerId, installationId);
        // 이 경로는 계획상 GitHub 연동 제한이 처음 붙는 자리다 — 프로젝트가 만들어지기 전에 막는다
        planService.ensureProviderConnectable(ownerId, IntegrationProvider.GITHUB);
        installationTokenService.getInstallationAccessToken(installationId);

        String normalizedRepositoryFullName = repositoryFullName.trim();
        String normalizedBranch = branch.trim();
        // 프로젝트 생성과 연동 저장을 한 트랜잭션에 묶는다 — 연동 저장이 실패하면 프로젝트도 함께
        // 롤백돼 GitHub 없는 빈 프로젝트가 남지 않는다. 갓 만든 프로젝트라 중복 연동 검사는 생략한다.
        Project project = transactionTemplate.execute(status -> {
            Project created = projectService.createProject(ownerId, name, description);
            saveGitHubIntegration(
                    created,
                    installation,
                    repositoryId,
                    normalizedRepositoryFullName,
                    normalizedBranch
            );
            return created;
        });
        planService.recordProviderConnected(ownerId, IntegrationProvider.GITHUB);
        pipelineWorkerClient.triggerCollection(IntegrationProvider.GITHUB, project.getId());
        return project;
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
        return saveGitHubIntegration(project, installation, repositoryId, repositoryFullName, branch);
    }

    private Integration saveGitHubIntegration(
            Project project,
            GitHubInstallation installation,
            Long repositoryId,
            String repositoryFullName,
            String branch
    ) {
        try {
            Integration integration = Integration.github(project, installation, repositoryId, repositoryFullName, branch);
            if (!planService.isIncrementalEnabled(project.getOwner().getId())) {
                integration.disableIncremental();
            }
            return integrationRepository.saveAndFlush(integration);
        } catch (DataIntegrityViolationException exception) {
            // 동시 연결 경합으로 사전 중복 검사를 통과한 경우 unique 제약 위반을 409로 변환
            throw integrationAlreadyExists(IntegrationProvider.GITHUB);
        }
    }

    /**
     * OAuth 동의 결과 저장 — 모든 OAuth provider가 공유하는 저장 정책.
     *
     * <p>확정 연동 409 선검사 → code 교환 → 자격증명 암호화 → 저장 → 수집 트리거까지가 여기 있고,
     * provider가 다른 부분은 flow가 돌려주는 {@link OAuthConnection}뿐이다 — 새 연동을 붙일 때
     * 이 메서드를 고칠 일이 없어야 한다.</p>
     *
     * <p>선택 단계 유무는 {@link IntegrationSelectionFlow} 등록 여부로 갈린다(선언한 provider는
     * pending 행으로 저장하고 수집은 {@link #completeSelection} 뒤로 미룬다) — flow가 따로 신고하게
     * 하면 두 SPI의 선언이 어긋나 영영 확정할 수 없는 행이 생길 수 있다.</p>
     */
    public ConnectResult connectOAuth(UUID ownerId, UUID projectId, OAuthConnectFlow flow, String code) {
        IntegrationProvider provider = flow.provider();
        projectService.getProject(ownerId, projectId);
        boolean requiresSelection = selectionFlows.find(provider).isPresent();
        // 이미 확정된 연동이면 code 교환으로 1회용 code를 낭비하지 않도록 외부 호출 전에 선검증.
        // 조회 결과는 아래 한도 검사에도 재사용한다(같은 요청에서 두 번 조회하지 않는다).
        Optional<Integration> existing = rejectIfAlreadyConnected(projectId, provider);
        if (existing.isEmpty()) {
            // 무료 티어 한도는 신규 연결에만 적용한다 — pending 행이 있다는 건 갱신 실패로 되돌아온
            // 기존 연동을 재동의로 복구하는 경로라, 이미 남은 이력을 다시 검사하면 복구 수단이 없어진다.
            // 같은 이유로 code 교환 전에 막아 1회용 code 낭비도 피한다.
            planService.ensureProviderConnectable(ownerId, provider);
        }

        OAuthConnection connection = flow.exchangeCode(code);
        if (requiresSelection && !connection.externalRef().isEmpty()) {
            // 두 SPI의 선언이 어긋난 배선 오류 — 저장했다면 확정 시 어차피 덮어써져 조용히 사라진다
            throw new IllegalStateException(provider.value()
                    + " declares selection steps, so exchangeCode must return OAuthConnection.pendingSelection().");
        }
        if (!requiresSelection && connection.externalRef().isEmpty()) {
            // 반대 방향의 배선 오류 — 확정할 selection flow가 없으니 pending 행이 영영 확정되지 못한다
            throw new IllegalStateException(provider.value()
                    + " does not declare selection steps, so exchangeCode must not return OAuthConnection.pendingSelection().");
        }
        byte[] encryptedCredential = credentialCryptoService.encrypt(connection.credential());

        // 외부 API 호출 중 DB 커넥션 점유를 피하기 위해 저장만 트랜잭션으로 분리
        Integration integration;
        try {
            integration = transactionTemplate.execute(status -> saveOAuthIntegration(
                    ownerId,
                    projectId,
                    provider,
                    connection.externalRef(),
                    encryptedCredential,
                    requiresSelection
            ));
        } catch (ConflictException exception) {
            // 사전 검사와 저장 사이 경합으로 확정 행이 생긴 경우 — 방금 교환한 자격증명은 저장되지
            // 않으므로 그대로 두면 provider 쪽에 grant만 남는다(Discord는 동의 승인 순간 서버에
            // 들어간 봇까지 남는데, guild_id를 저장하지 않았으니 나중에는 내보낼 수단도 없다).
            // 트랜잭션이 이미 끝난 자리라 외부 HTTP가 DB 커넥션을 점유하지 않는다.
            discardUnsavedConnection(provider, encryptedCredential, connection.externalRef());
            throw exception;
        }
        // 선택 단계가 있어도 provider 자체는 이미 연동 시도한 것이므로 확정 전에 이력을 남긴다
        planService.recordProviderConnected(ownerId, provider);

        if (!requiresSelection) {
            pipelineWorkerClient.triggerCollection(provider, projectId);
            return new ConnectResult(integration, false);
        }
        // 갱신 실패로 pending 복귀한 행(고른 값 보존)이면 재동의 직후 자동 복원을 시도한다.
        // 최초 연결의 pending 행에는 고른 값이 없으므로 이 분기를 자연히 타지 않는다.
        Integration restored = tryRestoreSelection(ownerId, projectId, provider, integration);
        return new ConnectResult(restored, !restored.isPendingSelection());
    }

    // Slack BYO(xoxp 붙여넣기) 연결 — OAuth grant가 아니라서 저장 실패 시 revoke하지 않는다
    public Integration connectSlackWorkspace(UUID ownerId, UUID projectId, String token) {
        projectService.getProject(ownerId, projectId);
        Optional<Integration> existing = rejectIfAlreadyConnected(projectId, IntegrationProvider.SLACK);
        if (existing.isEmpty()) {
            planService.ensureProviderConnectable(ownerId, IntegrationProvider.SLACK);
        }

        String trimmed = token.trim();
        SlackClient.SlackVerifiedUser verified;
        try {
            verified = slackClient.verifyToken(trimmed);
        } catch (UnauthorizedException exception) {
            // verifyToken의 401은 "JWT 없음"이 아니라 "붙여넣은 토큰이 무효"다.
            // 그대로 올리면 프론트 axios 인터셉터가 세션을 지운다.
            throw new BadRequestException(exception.getMessage());
        }
        // codec.encrypt는 여기서 암호화까지 하므로, 공용 crypto 경로와 맞추려면 serialize만 쓴다
        byte[] encryptedCredential = credentialCryptoService.encrypt(
                slackCredentialCodec.serialize(new SlackCredential(trimmed, null)));

        Integration integration = transactionTemplate.execute(status -> saveOAuthIntegration(
                ownerId,
                projectId,
                IntegrationProvider.SLACK,
                Map.of(
                        SlackOAuthConnectFlow.WORKSPACE_ID, verified.teamId(),
                        SlackOAuthConnectFlow.WORKSPACE_NAME, verified.teamName(),
                        SlackOAuthConnectFlow.CONNECTED_USER_ID, verified.userId(),
                        SlackOAuthConnectFlow.CONNECT_METHOD, SlackOAuthConnectFlow.CONNECT_METHOD_BYO
                ),
                encryptedCredential,
                false
        ));
        planService.recordProviderConnected(ownerId, IntegrationProvider.SLACK);
        pipelineWorkerClient.triggerCollection(IntegrationProvider.SLACK, projectId);
        return integration;
    }

    /**
     * 연동 저장 결과.
     *
     * @param selectionRestored 재동의로 이전 선택이 자동 복원돼 곧바로 확정됐는지. 프론트가 "선택하세요"
     *                          대신 "복원 완료" 배너를 띄우는 조건이라, 선택 단계가 없는 provider와
     *                          최초 연결에서는 false다.
     */
    public record ConnectResult(Integration integration, boolean selectionRestored) {}

    /**
     * 재동의로 얻은 새 토큰으로 이전 선택이 여전히 유효한지 확인해 자동 복원한다.
     *
     * <p>필수 단계를 앞에서부터 훑어 저장된 값이 아직 후보에 있는지 본다. 하나라도 사라졌으면
     * (다른 계정으로 동의했거나 대상이 삭제된 경우) pending을 유지해 사용자가 다시 고르게 한다.</p>
     */
    private Integration tryRestoreSelection(
            UUID ownerId,
            UUID projectId,
            IntegrationProvider provider,
            Integration integration
    ) {
        IntegrationSelectionFlow flow = selectionFlows.find(provider).orElse(null);
        if (flow == null) {
            return integration;
        }
        Map<String, String> stored = storedSelections(integration, flow);
        if (stored.isEmpty()) {
            return integration;
        }

        Map<String, String> verified = new LinkedHashMap<>();
        for (SelectionStep step : flow.steps()) {
            String value = stored.get(step.key());
            if (value == null) {
                if (step.optional()) continue;
                return integration;
            }
            try {
                boolean stillAvailable = flow.options(projectId, step.key(), Map.copyOf(verified)).stream()
                        .anyMatch(option -> option.value().equals(value));
                if (!stillAvailable) {
                    return integration;
                }
            } catch (RuntimeException exception) {
                // 이 시점엔 토큰 저장 트랜잭션이 이미 커밋된 뒤라 동의 자체는 성공했다. 조회 실패(네트워크
                // 오류 등)를 "연결 실패"로 알리면 실제 상태(새 토큰이 저장된 pending 행)와 어긋나므로,
                // 자동 복원만 포기하고 pending을 그대로 반환한다 — 사용자는 화면에서 바로 다시 고를 수 있다.
                log.warn("선택 후보 조회 실패로 자동 복원을 건너뜁니다. projectId={}, provider={}, step={}",
                        projectId, provider.value(), step.key(), exception);
                return integration;
            }
            verified.put(step.key(), value);
        }

        return completeSelection(ownerId, projectId, provider, storedExternalRef(integration, flow));
    }

    // provider가 선언한 선택 단계 (없으면 빈 목록 — 동의 직후 곧바로 확정되는 provider)
    public List<SelectionStep> selectionSteps(UUID ownerId, UUID projectId, IntegrationProvider provider) {
        projectService.getProject(ownerId, projectId);
        return selectionFlows.find(provider).map(IntegrationSelectionFlow::steps).orElseGet(List::of);
    }

    // 한 선택 단계의 후보 조회. selected에는 앞선 단계에서 고른 값들이 들어온다.
    public List<SelectionOption> selectionOptions(
            UUID ownerId,
            UUID projectId,
            IntegrationProvider provider,
            String stepKey,
            Map<String, String> selected
    ) {
        projectService.getProject(ownerId, projectId);
        IntegrationSelectionFlow flow = requireSelectionFlow(provider);
        if (flow.steps().stream().noneMatch(step -> step.key().equals(stepKey))) {
            throw new BadRequestException("Unknown selection step: " + stepKey);
        }
        return flow.options(projectId, stepKey, selected);
    }

    /**
     * 선택 확정. pending 행에만 허용하고, 토큰 확보 뒤 초기 수집을 트리거한다.
     *
     * @param selections external_ref에 그대로 저장될 키-값 (단계 key/labelKey → 값/표시 이름)
     */
    public Integration completeSelection(
            UUID ownerId,
            UUID projectId,
            IntegrationProvider provider,
            Map<String, Object> selections
    ) {
        projectService.getProject(ownerId, projectId);
        Integration integration = transactionTemplate.execute(status -> {
            Integration target = getIntegration(projectId, provider);
            if (!target.isPendingSelection()) {
                throw integrationAlreadyExists(provider);
            }
            target.applyExternalRef(selections);
            return integrationRepository.saveAndFlush(target);
        });
        // GitHub이 connectGitHubRepository에서 트리거 직전에 토큰을 갱신하는 것과 같은 자리 —
        // 방금 발급된 토큰이라 대개 그대로 재사용되지만, 사용자가 선택 화면에 오래 머문 경우를 대비한다.
        // 만료되지 않는 토큰을 쓰는 provider는 등록이 없어 건너뛴다(여기서는 no-op이 옳은 동작이다 —
        // 갱신 수단 부재를 오류로 알려야 하는 내부 토큰 API와 다르다).
        accessTokenRefreshers.find(provider)
                .ifPresent(refresher -> refresher.ensureFreshAccessToken(projectId));
        pipelineWorkerClient.triggerCollection(provider, projectId);
        return integration;
    }

    // 제출된 선택을 provider의 단계 선언에 맞춰 external_ref 형태로 조립한다.
    // 필수 단계가 비면 400 — 확정 후 수집이 대상을 못 찾는 상태를 만들지 않는다.
    public Map<String, Object> buildSelectionExternalRef(
            IntegrationProvider provider,
            Map<String, SelectionInput> submitted
    ) {
        IntegrationSelectionFlow flow = requireSelectionFlow(provider);
        Map<String, Object> externalRef = new LinkedHashMap<>();
        for (SelectionStep step : flow.steps()) {
            SelectionInput input = submitted.get(step.key());
            String value = input == null ? null : input.value();
            if (value == null || value.isBlank()) {
                if (step.optional()) continue;
                throw new BadRequestException("Missing required selection: " + step.key());
            }
            externalRef.put(step.key(), value);
            if (step.labelKey() != null && input.label() != null && !input.label().isBlank()) {
                externalRef.put(step.labelKey(), input.label());
            }
        }
        return externalRef;
    }

    private IntegrationSelectionFlow requireSelectionFlow(IntegrationProvider provider) {
        return selectionFlows.find(provider)
                .orElseThrow(() -> new NotFoundException(
                        provider.displayName() + " does not require target selection."));
    }

    private Map<String, String> storedSelections(Integration integration, IntegrationSelectionFlow flow) {
        Map<String, String> stored = new LinkedHashMap<>();
        for (SelectionStep step : flow.steps()) {
            String value = integration.externalRefValue(step.key());
            if (value != null) {
                stored.put(step.key(), value);
            }
        }
        return stored;
    }

    // 자동 복원은 저장된 값·표시 이름을 그대로 다시 쓴다 (status만 사라진다)
    private Map<String, Object> storedExternalRef(Integration integration, IntegrationSelectionFlow flow) {
        Map<String, Object> externalRef = new LinkedHashMap<>();
        for (SelectionStep step : flow.steps()) {
            String value = integration.externalRefValue(step.key());
            if (value == null) continue;
            externalRef.put(step.key(), value);
            if (step.labelKey() != null) {
                String label = integration.externalRefValue(step.labelKey());
                if (label != null) {
                    externalRef.put(step.labelKey(), label);
                }
            }
        }
        return externalRef;
    }

    // 재동의는 pending 행에만 허용한다 — 확정된 연동에는 409로 code 교환 전에 막는다.
    // 조회 결과(기존 행 존재 여부)를 호출부의 한도 검사 분기에도 재사용한다.
    private Optional<Integration> rejectIfAlreadyConnected(UUID projectId, IntegrationProvider provider) {
        Optional<Integration> existing = integrationRepository.findByProject_IdAndProvider(projectId, provider);
        existing.filter(integration -> !integration.isPendingSelection())
                .ifPresent(integration -> {
                    throw integrationAlreadyExists(provider);
                });
        return existing;
    }

    private Integration getIntegration(UUID projectId, IntegrationProvider provider) {
        return integrationRepository.findByProject_IdAndProvider(projectId, provider)
                .orElseThrow(() -> new NotFoundException(provider.displayName() + " integration not found."));
    }

    // 선택 확정 요청 한 건 — 값과 화면 표시 이름
    public record SelectionInput(String value, String label) {}

    // pending 행 재동의(경합 방어를 위해 트랜잭션 안에서 재확인) 또는 신규 생성
    private Integration saveOAuthIntegration(
            UUID ownerId,
            UUID projectId,
            IntegrationProvider provider,
            Map<String, Object> externalRef,
            byte[] encryptedCredential,
            boolean requiresSelection
    ) {
        Project project = projectService.getProject(ownerId, projectId);
        Optional<Integration> existing = integrationRepository.findByProject_IdAndProvider(projectId, provider);
        if (existing.isPresent()) {
            Integration integration = existing.get();
            if (!integration.isPendingSelection()) {
                // 사전 검사와 저장 사이 경합으로 그 사이 확정된 경우
                throw integrationAlreadyExists(provider);
            }
            integration.updateCredential(encryptedCredential);
            // 선택 단계가 있으면 pending을 유지한다 — 이미 고른 값이 남아 있으면 자동 복원이 다시 쓴다.
            // 없으면 동의만으로 대상이 정해지므로 방금 교환한 값으로 확정한다(토큰 갱신 영구 실패로
            // pending 복귀한 행을 재동의로 되살리는 경로 — 이 provider에는 확정할 다른 수단이 없다).
            if (!requiresSelection) {
                integration.applyExternalRef(externalRef);
            }
            return integrationRepository.saveAndFlush(integration);
        }
        try {
            Integration integration = requiresSelection
                    ? Integration.pendingSelection(project, provider, encryptedCredential)
                    : Integration.oauth(project, provider, externalRef, encryptedCredential);
            if (!planService.isIncrementalEnabled(ownerId)) {
                integration.disableIncremental();
            }
            return integrationRepository.saveAndFlush(integration);
        } catch (DataIntegrityViolationException exception) {
            // 동시 연결 경합 시 unique 제약 위반을 409로 변환
            throw integrationAlreadyExists(provider);
        }
    }

    /**
     * 연동 해제 — 자격증명·수집 커서·그 소스에서 수집한 그래프를 모두 제거한다.
     *
     * <p>프로젝트와 다른 provider의 연동은 유지된다. 사용자에게는 파괴적 동작이라
     * 프론트가 사전 경고를 띄운 뒤 호출한다.</p>
     */
    public void disconnect(UUID ownerId, UUID projectId, IntegrationProvider provider) {
        projectService.getProject(ownerId, projectId);
        Integration integration = integrationRepository.findByProject_IdAndProvider(projectId, provider)
                .orElseThrow(() -> new NotFoundException(provider.displayName() + " integration not found."));
        disconnectIntegration(integration);
    }

    /**
     * Slack app_uninstalled 이벤트 — 워크스페이스 전체 Slack 연동 해제.
     *
     * <p>JWT 없는 이벤트 경로라 projectService.getProject(소유권 검사)를 호출하지 않는다.
     * integrationRepository.findAllByProvider 후 Java 필터로 매칭한다 — 새 native query 없이
     * 기존 쿼리 재사용을 위해 intentional.</p>
     */
    public void disconnectSlackWorkspace(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return;
        }
        integrationRepository.findAllByProvider(IntegrationProvider.SLACK).stream()
                .filter(i -> workspaceId.equals(i.externalRefValue(SlackOAuthConnectFlow.WORKSPACE_ID)))
                .filter(i -> !isSlackByo(i))
                .forEach(this::disconnectSlackIntegrationQuietly);
    }

    /**
     * Slack tokens_revoked 이벤트 — oauth 사용자 목록 매칭 행 해제.
     *
     * <p>connected_user_id가 없는 레거시 행은 키 자체가 없으므로 externalRefValue가 null을 반환해 자동으로 건너뛴다.</p>
     */
    public void disconnectSlackUsers(String workspaceId, Collection<String> slackUserIds) {
        if (slackUserIds == null || slackUserIds.isEmpty()) {
            return;
        }
        integrationRepository.findAllByProvider(IntegrationProvider.SLACK).stream()
                .filter(i -> workspaceId != null && workspaceId.equals(i.externalRefValue(SlackOAuthConnectFlow.WORKSPACE_ID)))
                .filter(i -> !isSlackByo(i))
                .filter(i -> {
                    // externalRefValue가 null이면 contains(null)이 NPE — null 선검사 필요
                    String userId = i.externalRefValue(SlackOAuthConnectFlow.CONNECTED_USER_ID);
                    return userId != null && slackUserIds.contains(userId);
                })
                .forEach(this::disconnectSlackIntegrationQuietly);
    }

    /**
     * /why-code 슬래시 커맨드 대상 조회.
     *
     * <p>트랜잭션 안에서 프로젝트·소유자·자격증명을 DTO로 복사한다 — 커맨드 질의는 executor 스레드에서
     * 돌므로 lazy 프록시를 비동기 밖으로 내보내면 LazyInitializationException이 난다.</p>
     */
    public List<SlackCommandTarget> listSlackCommandTargets(String workspaceId) {
        return transactionTemplate.execute(status ->
                integrationRepository.findAllByProvider(IntegrationProvider.SLACK).stream()
                        .filter(i -> workspaceId.equals(i.externalRefValue(SlackOAuthConnectFlow.WORKSPACE_ID)))
                        .filter(i -> !isSlackByo(i))
                        .filter(i -> !i.isPendingSelection())
                        .map(this::toSlackCommandTarget)
                        .toList());
    }

    // connected_user_id가 없던 레거시 행을 auth.test 결과로 승급. 다른 external_ref 키는 유지한다.
    public void backfillSlackConnectedUserId(UUID integrationId, String slackUserId) {
        transactionTemplate.executeWithoutResult(status -> {
            Integration integration = integrationRepository.findById(integrationId)
                    .orElseThrow(() -> new NotFoundException("Slack integration not found."));
            Map<String, Object> updated = new HashMap<>(integration.getExternalRef());
            updated.put(SlackOAuthConnectFlow.CONNECTED_USER_ID, slackUserId);
            integration.applyExternalRef(updated);
        });
    }

    // 키 없는 레거시·OAuth는 false — app_uninstalled/tokens_revoked는 OAuth grant를 끊는 이벤트라 BYO 행은 대상이 아니다
    private boolean isSlackByo(Integration integration) {
        return SlackOAuthConnectFlow.CONNECT_METHOD_BYO.equals(
                integration.externalRefValue(SlackOAuthConnectFlow.CONNECT_METHOD));
    }

    private SlackCommandTarget toSlackCommandTarget(Integration integration) {
        Project project = integration.getProject();
        return new SlackCommandTarget(
                integration.getId(),
                project.getId(),
                project.getName(),
                project.getOwner().getId(),
                integration.externalRefValue(SlackOAuthConnectFlow.CONNECTED_USER_ID),
                integration.getEncryptedCredential());
    }

    public record SlackCommandTarget(
            UUID integrationId,
            UUID projectId,
            String projectName,
            UUID ownerUserId,
            String connectedUserId,
            byte[] encryptedCredential
    ) {
    }

    // 이벤트 경로 전용 — 한 행의 그래프 삭제 실패가 같은 워크스페이스의 다른 행 해제를 막으면 안 된다.
    // JWT disconnect()는 단건이라 disconnectIntegration을 직접 호출해 예외를 사용자에게 그대로 보낸다.
    private void disconnectSlackIntegrationQuietly(Integration integration) {
        try {
            disconnectIntegration(integration);
        } catch (RuntimeException e) {
            log.error("Slack lifecycle disconnect failed. integrationId={}, error={}",
                    integration.getId(), e.getMessage(), e);
        }
    }

    // 폐기 → 그래프 삭제 → RDB 삭제 공통 경로 (소유권 검사 없음 — 이벤트 경로에서 호출 가능)
    private void disconnectIntegration(Integration integration) {
        UUID projectId = integration.getProject().getId();
        IntegrationProvider provider = integration.getProvider();
        // provider 쪽 권한 폐기를 먼저 한다 — 우리 DB의 토큰을 지우면 폐기에 쓸 값 자체가 사라진다.
        // 실패해도 진행한다(각 client가 삼킨다): 이미 폐기된 토큰이나 provider 장애 때문에
        // 해제가 막히면 사용자가 데이터를 지울 방법을 잃는다.
        revocationService.revoke(integration);
        // 그래프를 먼저 지우는 순서·이유는 프로젝트 삭제와 같다(ProjectService.deleteProject 주석):
        // 외부 HTTP를 트랜잭션 밖에 둬 커넥션 점유를 피하고, 그래프 삭제가 멱등이라 RDB 삭제가
        // 뒤에서 실패해도 재시도 시 no-op으로 통과해 수렴한다(원자적 보장은 아님).
        aiEngineGraphClient.deleteProjectSourceGraph(projectId, provider);
        transactionTemplate.executeWithoutResult(status -> {
            // checkpoint를 남기면 재연결이 옛 커서부터 증분 수집을 재개해 그 사이 데이터가 누락된다
            checkpointRepository.deleteByProject_IdAndId_Provider(projectId, provider);
            integrationRepository.deleteById(integration.getId());
        });
    }

    // 프로젝트당 provider별 1개 연동 제한 검증
    private void validateProviderAvailable(UUID projectId, IntegrationProvider provider) {
        if (integrationRepository.existsByProject_IdAndProvider(projectId, provider)) {
            throw integrationAlreadyExists(provider);
        }
    }

    // 저장되지 못한 자격증명 폐기 — 해제와 같은 SPI를 쓴다(등록이 없는 provider는 find()가 비어
    // 아무 일도 하지 않는다).
    // 정리 실패가 원래의 409를 가리면 사용자는 "왜 실패했는지"를 잃으므로 예외를 삼키고 로그만 남긴다.
    private void discardUnsavedConnection(
            IntegrationProvider provider,
            byte[] encryptedCredential,
            Map<String, Object> externalRef
    ) {
        try {
            credentialLifecycles.find(provider)
                    .ifPresent(lifecycle -> lifecycle.revoke(encryptedCredential, externalRef));
        } catch (RuntimeException exception) {
            log.warn("Failed to discard unsaved OAuth connection. provider={}, error={}",
                    provider.value(), exception.getMessage());
        }
    }

    private ConflictException integrationAlreadyExists(IntegrationProvider provider) {
        return new ConflictException(provider.displayName() + " integration already exists.");
    }
}
