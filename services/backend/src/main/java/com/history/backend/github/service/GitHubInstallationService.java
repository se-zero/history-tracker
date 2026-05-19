package com.history.backend.github.service;

import java.util.List;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.dto.GitHubInstallationResponse;
import com.history.backend.github.dto.InstallationResponse;
import com.history.backend.github.repository.GitHubInstallationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GitHubInstallationService {

    private final GitHubInstallationRepository gitHubInstallationRepository;

    @Transactional
    public GitHubInstallation upsertInstallation(User installer, GitHubInstallationResponse response) {
        return gitHubInstallationRepository.findByInstallationId(response.id())
                .map(installation -> {
                    installation.updateAccount(response.account().type(), response.account().login(), installer);
                    return installation;
                })
                .orElseGet(() -> createInstallation(installer, response));
    }

    private GitHubInstallation createInstallation(User installer, GitHubInstallationResponse response) {
        return gitHubInstallationRepository.insertInstallationIfAbsent(
                        response.id(),
                        response.account().type(),
                        response.account().login(),
                        installer.getId()
                )
                .flatMap(gitHubInstallationRepository::findById)
                .or(() -> gitHubInstallationRepository.findByInstallationId(response.id()))
                .map(installation -> {
                    installation.updateAccount(response.account().type(), response.account().login(), installer);
                    return installation;
                })
                .orElseThrow(() -> new IllegalStateException("Failed to create or load GitHub installation."));
    }

    @Transactional(readOnly = true)
    public List<InstallationResponse> findInstallations(UUID installerId) {
        return gitHubInstallationRepository.findAllByInstallerUser_Id(installerId).stream()
                .map(InstallationResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public GitHubInstallation getInstallationForInstaller(UUID installerId, UUID installationId) {
        return gitHubInstallationRepository.findByIdAndInstallerUser_Id(installationId, installerId)
                .orElseThrow(() -> new NotFoundException("GitHub installation not found."));
    }
}
