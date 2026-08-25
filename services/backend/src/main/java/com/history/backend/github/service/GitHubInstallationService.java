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
import com.history.backend.github.repository.GitHubInstallationMemberRepository;
import com.history.backend.github.repository.GitHubInstallationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GitHubInstallationService {

    private final GitHubInstallationRepository gitHubInstallationRepository;
    private final GitHubInstallationMemberRepository gitHubInstallationMemberRepository;
    private final GitHubAppClient gitHubAppClient;
    private final InstallationTokenService installationTokenService;
    private final UserService userService;

    // installation 저장 또는 계정 정보 갱신 후 동기화한 사용자를 멤버로 등록(멱등)
    @Transactional
    public GitHubInstallation upsertInstallation(User installer, GitHubInstallationResponse response) {
        GitHubInstallation installation = gitHubInstallationRepository.findByInstallationId(response.id())
                .map(existing -> {
                    existing.updateAccount(response.account().type(), response.account().login());
                    return existing;
                })
                .orElseGet(() -> createInstallation(installer, response));
        gitHubInstallationMemberRepository.addMember(installation.getId(), installer.getId());
        return installation;
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
                    installation.updateAccount(response.account().type(), response.account().login());
                    return installation;
                })
                .orElseThrow(() -> new IllegalStateException("Failed to create or load GitHub installation."));
    }

    @Transactional(readOnly = true)
    public List<InstallationResponse> findInstallations(UUID userId) {
        userService.getActiveUser(userId);
        return gitHubInstallationRepository.findAllByMemberUserId(userId).stream()
                .map(InstallationResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public GitHubInstallation getAccessibleInstallation(UUID userId, UUID installationId) {
        userService.getActiveUser(userId);
        return gitHubInstallationRepository.findByIdAndMemberUserId(installationId, userId)
                .orElseThrow(() -> new NotFoundException("GitHub installation not found."));
    }

    public List<RepositoryResponse> findRepositories(UUID userId, UUID installationId) {
        GitHubInstallation installation = getAccessibleInstallation(userId, installationId);
        String installationAccessToken = installationTokenService.getInstallationAccessToken(installation.getId());
        return gitHubAppClient.fetchInstallationRepositories(installationAccessToken).stream()
                .map(RepositoryResponse::from)
                .toList();
    }

    public List<String> findRepositoryBranches(
            UUID userId,
            UUID installationId,
            String owner,
            String repo
    ) {
        GitHubInstallation installation = getAccessibleInstallation(userId, installationId);
        String installationAccessToken = installationTokenService.getInstallationAccessToken(installation.getId());
        return gitHubAppClient.fetchRepositoryBranches(installationAccessToken, owner, repo);
    }
}
