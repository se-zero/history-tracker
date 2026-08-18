package com.history.backend.project.dto;

import com.history.backend.integration.dto.ConnectGitHubIntegrationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// github이 있으면 프로젝트와 GitHub 연동을 한 트랜잭션으로 만든다(온보딩). 없으면 프로젝트만 만든다.
public record CreateProjectRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2000) String description,
        @Valid ConnectGitHubIntegrationRequest github
) {
}
