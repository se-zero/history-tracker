package com.history.backend.integration.controller;

import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.common.error.ConflictException;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.dto.IntegrationResponse;
import com.history.backend.integration.service.IntegrationService;
import com.history.backend.project.domain.Project;
import com.history.backend.security.AuthenticatedUser;
import com.history.backend.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("IntegrationController: 연동 HTTP API")
class IntegrationControllerTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");
    private static final UUID INSTALLATION_ID = UUID.fromString("45b30a75-46d0-4402-b842-9e9c7d07e9ab");
    private static final UUID INTEGRATION_ID = UUID.fromString("72b9c869-77f6-4b4d-b8c5-db85023ef3b8");
    private static final Instant CREATED_AT = Instant.parse("2026-05-19T01:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-05-19T02:00:00Z");
    private static final Instant SYNCED_AT = Instant.parse("2026-06-15T03:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IntegrationService integrationService;

    @MockitoBean
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUpAuthentication() {
        when(jwtTokenService.validateAccessToken(anyString())).thenReturn(new AuthenticatedUser(USER_ID));
    }

    @Test
    @DisplayName("프로젝트 연동 목록 조회")
    void listIntegrationsReturnsIntegrationsForProject() throws Exception {
        when(integrationService.listIntegrations(USER_ID, PROJECT_ID))
                .thenReturn(List.of(IntegrationResponse.from(integration(), SYNCED_AT)));

        mockMvc.perform(get("/api/v1/projects/{projectId}/integrations", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(INTEGRATION_ID.toString()))
                .andExpect(jsonPath("$[0].projectId").value(PROJECT_ID.toString()))
                .andExpect(jsonPath("$[0].provider").value("github"))
                .andExpect(jsonPath("$[0].displayName").value("acme/widget"))
                .andExpect(jsonPath("$[0].installationId").value(INSTALLATION_ID.toString()))
                .andExpect(jsonPath("$[0].metadata.repository_id").value(12345))
                .andExpect(jsonPath("$[0].metadata.repository_full_name").value("acme/widget"))
                .andExpect(jsonPath("$[0].lastSyncedAt").value("2026-06-15T03:00:00Z"));
    }

    @Test
    @DisplayName("GitHub 리포지토리 연동 → 201 Created 반환")
    void connectGitHubRepositoryReturnsCreatedIntegration() throws Exception {
        when(integrationService.connectGitHubRepository(
                USER_ID,
                PROJECT_ID,
                INSTALLATION_ID,
                12345L,
                "acme/widget",
                "main"
        )).thenReturn(integration());

        mockMvc.perform(post("/api/v1/projects/{projectId}/integrations/github", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "installation_id": "45b30a75-46d0-4402-b842-9e9c7d07e9ab",
                                  "repository_id": 12345,
                                  "repository_full_name": "acme/widget",
                                  "branch": "main"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(INTEGRATION_ID.toString()))
                .andExpect(jsonPath("$.projectId").value(PROJECT_ID.toString()))
                .andExpect(jsonPath("$.provider").value("github"))
                .andExpect(jsonPath("$.displayName").value("acme/widget"))
                .andExpect(jsonPath("$.installationId").value(INSTALLATION_ID.toString()))
                .andExpect(jsonPath("$.metadata.repository_id").value(12345))
                .andExpect(jsonPath("$.metadata.repository_full_name").value("acme/widget"))
                .andExpect(jsonPath("$.metadata.branch").value("main"))
                .andExpect(jsonPath("$.externalRef").doesNotExist())
                .andExpect(jsonPath("$.createdAt").value("2026-05-19T01:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-05-19T02:00:00Z"));
    }

    @Test
    @DisplayName("Slack 워크스페이스 연동 → 201 Created 반환")
    void connectSlackWorkspaceReturnsCreatedIntegration() throws Exception {
        when(integrationService.connectSlackWorkspace(
                USER_ID,
                PROJECT_ID,
                "xoxb-token"
        )).thenReturn(slackIntegration());

        mockMvc.perform(post("/api/v1/projects/{projectId}/integrations/slack", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "xoxb-token"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(INTEGRATION_ID.toString()))
                .andExpect(jsonPath("$.projectId").value(PROJECT_ID.toString()))
                .andExpect(jsonPath("$.provider").value("slack"))
                .andExpect(jsonPath("$.displayName").value("Acme"))
                .andExpect(jsonPath("$.installationId").doesNotExist())
                .andExpect(jsonPath("$.metadata.workspace_id").value("T123"))
                .andExpect(jsonPath("$.metadata.workspace_name").value("Acme"))
                .andExpect(jsonPath("$.metadata.token").doesNotExist())
                .andExpect(jsonPath("$.externalRef").doesNotExist())
                .andExpect(jsonPath("$.createdAt").value("2026-05-19T01:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-05-19T02:00:00Z"));
    }

    @Test
    @DisplayName("Jira 프로젝트 연동 → 201 Created 반환")
    void connectJiraProjectReturnsCreatedIntegration() throws Exception {
        when(integrationService.connectJiraProject(
                USER_ID,
                PROJECT_ID,
                "https://example.atlassian.net",
                "PROJ",
                "owner@example.com",
                "jira-token"
        )).thenReturn(jiraIntegration());

        mockMvc.perform(post("/api/v1/projects/{projectId}/integrations/jira", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "base_url": "https://example.atlassian.net",
                                  "project_key": "PROJ",
                                  "email": "owner@example.com",
                                  "api_token": "jira-token"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(INTEGRATION_ID.toString()))
                .andExpect(jsonPath("$.projectId").value(PROJECT_ID.toString()))
                .andExpect(jsonPath("$.provider").value("jira"))
                .andExpect(jsonPath("$.displayName").value("Project"))
                .andExpect(jsonPath("$.installationId").doesNotExist())
                .andExpect(jsonPath("$.metadata.project_key").value("PROJ"))
                .andExpect(jsonPath("$.metadata.project_name").value("Project"))
                .andExpect(jsonPath("$.metadata.base_url").value("https://example.atlassian.net"))
                .andExpect(jsonPath("$.metadata.email").doesNotExist())
                .andExpect(jsonPath("$.metadata.api_token").doesNotExist())
                .andExpect(jsonPath("$.externalRef").doesNotExist())
                .andExpect(jsonPath("$.createdAt").value("2026-05-19T01:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-05-19T02:00:00Z"));
    }

    @Test
    @DisplayName("유효하지 않은 GitHub 연동 요청 → 400 Bad Request 반환")
    void connectGitHubRepositoryRejectsInvalidRequest() throws Exception {
        mockMvc.perform(post("/api/v1/projects/{projectId}/integrations/github", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "installation_id": "45b30a75-46d0-4402-b842-9e9c7d07e9ab",
                                  "repository_id": 0,
                                  "repository_full_name": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed."));
    }

    @Test
    @DisplayName("유효하지 않은 Slack 연동 요청 → 400 Bad Request 반환")
    void connectSlackWorkspaceRejectsInvalidRequest() throws Exception {
        mockMvc.perform(post("/api/v1/projects/{projectId}/integrations/slack", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed."));
    }

    @Test
    @DisplayName("유효하지 않은 Jira 연동 요청 → 400 Bad Request 반환")
    void connectJiraProjectRejectsInvalidRequest() throws Exception {
        mockMvc.perform(post("/api/v1/projects/{projectId}/integrations/jira", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "base_url": "ftp://example.atlassian.net",
                                  "project_key": "bad key",
                                  "email": "not-email",
                                  "api_token": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed."));
    }

    @Test
    @DisplayName("잘못된 형식의 GitHub 리포지토리 이름 거부")
    void connectGitHubRepositoryRejectsInvalidRepositoryFullNameFormat() throws Exception {
        mockMvc.perform(post("/api/v1/projects/{projectId}/integrations/github", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "installation_id": "45b30a75-46d0-4402-b842-9e9c7d07e9ab",
                                  "repository_id": 12345,
                                  "repository_full_name": "acme/platform/widget",
                                  "branch": "main"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed."))
                .andExpect(jsonPath("$.fields[0].field").value("repositoryFullName"));
    }

    @Test
    @DisplayName("이미 연동된 GitHub 리포지토리 → 409 Conflict 반환")
    void connectGitHubRepositoryReturnsConflictWhenAlreadyConnected() throws Exception {
        when(integrationService.connectGitHubRepository(
                USER_ID,
                PROJECT_ID,
                INSTALLATION_ID,
                12345L,
                "acme/widget",
                "main"
        )).thenThrow(new ConflictException("GitHub integration already exists."));

        mockMvc.perform(post("/api/v1/projects/{projectId}/integrations/github", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "installation_id": "45b30a75-46d0-4402-b842-9e9c7d07e9ab",
                                  "repository_id": 12345,
                                  "repository_full_name": "acme/widget",
                                  "branch": "main"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("GitHub integration already exists."));
    }

    @Test
    @DisplayName("이미 연동된 Slack 워크스페이스 → 409 Conflict 반환")
    void connectSlackWorkspaceReturnsConflictWhenAlreadyConnected() throws Exception {
        when(integrationService.connectSlackWorkspace(
                USER_ID,
                PROJECT_ID,
                "xoxb-token"
        )).thenThrow(new ConflictException("Slack integration already exists."));

        mockMvc.perform(post("/api/v1/projects/{projectId}/integrations/slack", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "xoxb-token"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Slack integration already exists."));
    }

    @Test
    @DisplayName("이미 연동된 Jira 프로젝트 → 409 Conflict 반환")
    void connectJiraProjectReturnsConflictWhenAlreadyConnected() throws Exception {
        when(integrationService.connectJiraProject(
                USER_ID,
                PROJECT_ID,
                "https://example.atlassian.net",
                "PROJ",
                "owner@example.com",
                "jira-token"
        )).thenThrow(new ConflictException("Jira integration already exists."));

        mockMvc.perform(post("/api/v1/projects/{projectId}/integrations/jira", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "base_url": "https://example.atlassian.net",
                                  "project_key": "PROJ",
                                  "email": "owner@example.com",
                                  "api_token": "jira-token"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Jira integration already exists."));
    }

    @Test
    @DisplayName("액세스 토큰 없으면 401 Unauthorized 반환")
    void rejectMissingAccessToken() throws Exception {
        mockMvc.perform(post("/api/v1/projects/{projectId}/integrations/github", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "installation_id": "45b30a75-46d0-4402-b842-9e9c7d07e9ab",
                                  "repository_id": 12345,
                                  "repository_full_name": "acme/widget"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication is required."));
    }

    private Integration integration() {
        User owner = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(owner, "id", USER_ID);

        Project project = new Project(owner, "History Tracker", null);
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);

        GitHubInstallation installation = new GitHubInstallation(98765L, "Organization", "acme", owner);
        ReflectionTestUtils.setField(installation, "id", INSTALLATION_ID);

        Integration integration = Integration.github(project, installation, 12345L, "acme/widget", "main");
        ReflectionTestUtils.setField(integration, "id", INTEGRATION_ID);
        ReflectionTestUtils.setField(integration, "createdAt", CREATED_AT);
        ReflectionTestUtils.setField(integration, "updatedAt", UPDATED_AT);
        return integration;
    }

    private Integration slackIntegration() {
        User owner = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(owner, "id", USER_ID);

        Project project = new Project(owner, "History Tracker", null);
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);

        Integration integration = Integration.slack(project, "T123", "Acme", new byte[] {1, 2, 3});
        ReflectionTestUtils.setField(integration, "id", INTEGRATION_ID);
        ReflectionTestUtils.setField(integration, "createdAt", CREATED_AT);
        ReflectionTestUtils.setField(integration, "updatedAt", UPDATED_AT);
        return integration;
    }

    private Integration jiraIntegration() {
        User owner = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(owner, "id", USER_ID);

        Project project = new Project(owner, "History Tracker", null);
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);

        Integration integration = Integration.jira(
                project,
                "PROJ",
                "Project",
                "https://example.atlassian.net",
                new byte[] {4, 5, 6}
        );
        ReflectionTestUtils.setField(integration, "id", INTEGRATION_ID);
        ReflectionTestUtils.setField(integration, "createdAt", CREATED_AT);
        ReflectionTestUtils.setField(integration, "updatedAt", UPDATED_AT);
        return integration;
    }
}
