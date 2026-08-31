package com.history.backend.github.service;

import java.util.Collection;
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
    private final UserService userService;
    private final GitHubUserTokenService gitHubUserTokenService;
    private final GitHubOAuthClient gitHubOAuthClient;

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

    // 로그인 동기화가 이번에 접근 가능하다고 확인한 설치(keptInstallationIds)만 남기고
    // 나머지 멤버십을 지운다 — 조직 이탈 등으로 GitHub이 더 이상 돌려주지 않는 설치의 멤버십이
    // 영원히 남는 것을 막는다.
    @Transactional
    public void pruneMemberships(UUID userId, Collection<UUID> keptInstallationIds) {
        gitHubInstallationMemberRepository.pruneMemberships(userId, keptInstallationIds);
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

    // 설치 토큰이 아니라 user token으로 조회한다 — 설치 토큰은 설치에 열린 저장소 전부를
    // 보여 사용자가 접근할 수 없는 비공개 저장소까지 연결 후보로 노출한다.
    public List<RepositoryResponse> findRepositories(UUID userId, UUID installationId) {
        GitHubInstallation installation = getAccessibleInstallation(userId, installationId);
        String userAccessToken = gitHubUserTokenService.getAccessToken(userId);
        return gitHubOAuthClient.fetchUserInstallationRepositories(
                        userAccessToken,
                        installation.getInstallationId()
                )
                .stream()
                .map(RepositoryResponse::from)
                .toList();
    }

    public List<String> findRepositoryBranches(
            UUID userId,
            UUID installationId,
            String owner,
            String repo
    ) {
        getAccessibleInstallation(userId, installationId);
        String userAccessToken = gitHubUserTokenService.getAccessToken(userId);
        return gitHubOAuthClient.fetchRepositoryBranches(userAccessToken, owner, repo);
    }
}
