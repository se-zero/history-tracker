package com.history.backend.github.service;

import java.util.List;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.service.UserService;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.dto.GitHubInstallationResponse;
import com.history.backend.github.dto.InstallationResponse;
import com.history.backend.github.dto.RepositoryResponse;
import com.history.backend.github.repository.GitHubInstallationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GitHubInstallationService {

    private final GitHubInstallationRepository gitHubInstallationRepository;
    private final GitHubAppClient gitHubAppClient;
    private final InstallationTokenService installationTokenService;
    private final UserService userService;

    // installation 저장 또는 계정·설치자 정보 갱신
    @Transactional
    public GitHubInstallation upsertInstallation(User installer, GitHubInstallationResponse response) {
        return gitHubInstallationRepository.findByInstallationId(response.id())
                .map(installation -> {
                    installation.updateAccount(response.account().type(), response.account().login(), installer);
                    return installation;
                })
                .orElseGet(() -> createInstallation(installer, response));
    }

    // 동시 설치 콜백 경합에 안전한 installation 생성
    private GitHubInstallation createInstallation(User installer, GitHubInstallationResponse response) {
        return gitHubInstallationRepository.insertInstallationIfAbsent(
                        response.id(),
                        response.account().type(),
                        response.account().login(),
                        installer.getId()
                )
                .flatMap(gitHubInstallationRepository::findById)
                // 경합으로 insert가 무시된 경우 먼저 생성된 installation을 재조회해 사용
                .or(() -> gitHubInstallationRepository.findByInstallationId(response.id()))
                .map(installation -> {
                    installation.updateAccount(response.account().type(), response.account().login(), installer);
                    return installation;
                })
                .orElseThrow(() -> new IllegalStateException("Failed to create or load GitHub installation."));
    }

    @Transactional(readOnly = true)
    public List<InstallationResponse> findInstallations(UUID installerId) {
        userService.getActiveUser(installerId);
        return gitHubInstallationRepository.findAllByInstallerUser_Id(installerId).stream()
                .map(InstallationResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public GitHubInstallation getInstallationForInstaller(UUID installerId, UUID installationId) {
        userService.getActiveUser(installerId);
        return gitHubInstallationRepository.findByIdAndInstallerUser_Id(installationId, installerId)
                .orElseThrow(() -> new NotFoundException("GitHub installation not found."));
    }

    public List<RepositoryResponse> findRepositories(UUID installerId, UUID installationId) {
        GitHubInstallation installation = getInstallationForInstaller(installerId, installationId);
        String installationAccessToken = installationTokenService.getInstallationAccessToken(installation.getId());
        return gitHubAppClient.fetchInstallationRepositories(installationAccessToken).stream()
                .map(RepositoryResponse::from)
                .toList();
    }

    public List<String> findRepositoryBranches(
            UUID installerId,
            UUID installationId,
            String owner,
            String repo
    ) {
        GitHubInstallation installation = getInstallationForInstaller(installerId, installationId);
        String installationAccessToken = installationTokenService.getInstallationAccessToken(installation.getId());
        return gitHubAppClient.fetchRepositoryBranches(installationAccessToken, owner, repo);
    }
}
