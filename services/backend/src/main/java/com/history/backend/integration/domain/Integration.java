package com.history.backend.integration.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.project.domain.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

// 프로젝트 외부 연동 (provider별 메타데이터는 external_ref JSON 컬럼에 저장)
@Getter
@Entity
@Table(name = "integrations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Integration {

    public static final String GITHUB_REPOSITORY_ID = "repository_id";
    public static final String GITHUB_REPOSITORY_FULL_NAME = "repository_full_name";
    public static final String GITHUB_BRANCH = "branch";
    public static final String SLACK_WORKSPACE_ID = "workspace_id";
    public static final String SLACK_WORKSPACE_NAME = "workspace_name";
    public static final String JIRA_PROJECT_KEY = "project_key";
    public static final String JIRA_PROJECT_NAME = "project_name";
    public static final String JIRA_BASE_URL = "base_url";

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false)
    @Convert(converter = IntegrationProviderConverter.class)
    private IntegrationProvider provider;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "external_ref", nullable = false)
    private Map<String, Object> externalRef;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "installation_id")
    private GitHubInstallation installation;

    @Column(name = "encrypted_credential")
    private byte[] encryptedCredential;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Integration github(
            Project project,
            GitHubInstallation installation,
            Long repositoryId,
            String repositoryFullName,
            String branch
    ) {
        Map<String, Object> externalRef = new HashMap<>();
        externalRef.put(GITHUB_REPOSITORY_ID, repositoryId);
        externalRef.put(GITHUB_REPOSITORY_FULL_NAME, repositoryFullName);
        if (branch != null && !branch.isBlank()) {
            externalRef.put(GITHUB_BRANCH, branch);
        }
        return new Integration(
                project,
                IntegrationProvider.GITHUB,
                Map.copyOf(externalRef),
                installation,
                null
        );
    }

    public static Integration slack(
            Project project,
            String workspaceId,
            String workspaceName,
            byte[] encryptedCredential
    ) {
        return new Integration(
                project,
                IntegrationProvider.SLACK,
                Map.of(
                        SLACK_WORKSPACE_ID, workspaceId,
                        SLACK_WORKSPACE_NAME, workspaceName
                ),
                null,
                encryptedCredential
        );
    }

    public static Integration jira(
            Project project,
            String projectKey,
            String projectName,
            String baseUrl,
            byte[] encryptedCredential
    ) {
        Map<String, Object> externalRef = new HashMap<>();
        externalRef.put(JIRA_PROJECT_KEY, projectKey);
        externalRef.put(JIRA_BASE_URL, baseUrl);
        if (projectName != null && !projectName.isBlank()) {
            externalRef.put(JIRA_PROJECT_NAME, projectName);
        }
        return new Integration(
                project,
                IntegrationProvider.JIRA,
                Map.copyOf(externalRef),
                null,
                encryptedCredential
        );
    }

    private Integration(
            Project project,
            IntegrationProvider provider,
            Map<String, Object> externalRef,
            GitHubInstallation installation,
            byte[] encryptedCredential
    ) {
        this.project = project;
        this.provider = provider;
        this.externalRef = externalRef;
        this.installation = installation;
        // byte[] 가변성 차단을 위한 방어적 복사
        this.encryptedCredential = encryptedCredential == null
                ? null
                : Arrays.copyOf(encryptedCredential, encryptedCredential.length);
    }

    public byte[] getEncryptedCredential() {
        // byte[] 가변성 차단을 위한 방어적 복사
        return encryptedCredential == null
                ? null
                : Arrays.copyOf(encryptedCredential, encryptedCredential.length);
    }

    public Long getGitHubRepositoryId() {
        Object repositoryId = externalRef.get(GITHUB_REPOSITORY_ID);
        if (repositoryId instanceof Number number) {
            return number.longValue();
        }
        if (repositoryId == null) {
            throw new IllegalStateException("Missing GitHub repository_id.");
        }
        throw new IllegalStateException("Unexpected GitHub repository_id type: " + repositoryId.getClass());
    }

    public String getGitHubRepositoryFullName() {
        return getRequiredString(GITHUB_REPOSITORY_FULL_NAME, "GitHub repository_full_name");
    }

    public String getGitHubBranch() {
        Object branch = externalRef.get(GITHUB_BRANCH);
        return branch instanceof String value ? value : null;
    }

    public String getSlackWorkspaceId() {
        return getRequiredString(SLACK_WORKSPACE_ID, "Slack workspace_id");
    }

    public String getSlackWorkspaceName() {
        return getRequiredString(SLACK_WORKSPACE_NAME, "Slack workspace_name");
    }

    public String getJiraProjectKey() {
        return getRequiredString(JIRA_PROJECT_KEY, "Jira project_key");
    }

    public String getJiraProjectName() {
        Object value = externalRef.get(JIRA_PROJECT_NAME);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        if (value == null) {
            return null;
        }
        throw new IllegalStateException("Unexpected Jira project_name type: " + value.getClass());
    }

    public String getJiraBaseUrl() {
        return getRequiredString(JIRA_BASE_URL, "Jira base_url");
    }

    private String getRequiredString(String key, String label) {
        Object value = externalRef.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        if (value == null) {
            throw new IllegalStateException("Missing " + label + ".");
        }
        throw new IllegalStateException("Unexpected " + label + " type: " + value.getClass());
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
