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
    public static final String JIRA_CLOUD_ID = "cloud_id";
    public static final String JIRA_SITE_NAME = "site_name";
    public static final String JIRA_STATUS = "status";
    public static final String JIRA_STATUS_PENDING_PROJECT = "pending_project";

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

    // Jira는 동의 직후 사이트·프로젝트를 아직 모르므로 토큰만 담은 pending 행으로 시작한다.
    // 사용자가 사이트·프로젝트를 고르면 completeJiraProject로 확정한다.
    public static Integration jiraPending(Project project, byte[] encryptedCredential) {
        return new Integration(
                project,
                IntegrationProvider.JIRA,
                Map.of(JIRA_STATUS, JIRA_STATUS_PENDING_PROJECT),
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

    public String getJiraSiteName() {
        Object value = externalRef.get(JIRA_SITE_NAME);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    public String getJiraCloudId() {
        Object value = externalRef.get(JIRA_CLOUD_ID);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    public boolean isJiraPendingProject() {
        return JIRA_STATUS_PENDING_PROJECT.equals(externalRef.get(JIRA_STATUS));
    }

    // 갱신 실패로 pending 복귀한 행인지 판별 — cloud_id·project_key가 남아 있으면 자동 복원 대상이다.
    // 최초 연결의 pending 행(status만 있음)은 이 값들이 없어 자연히 자동 복원 분기를 타지 않는다.
    public boolean hasRestorableJiraProject() {
        return getJiraCloudId() != null && hasJiraProjectKey();
    }

    private boolean hasJiraProjectKey() {
        Object value = externalRef.get(JIRA_PROJECT_KEY);
        return value instanceof String text && !text.isBlank();
    }

    // pending 행 재동의 시 토큰 교체 (토큰 갱신도 이 메서드를 재사용한다)
    public void updateCredential(byte[] encryptedCredential) {
        this.encryptedCredential = Arrays.copyOf(encryptedCredential, encryptedCredential.length);
    }

    // 토큰 갱신 영구 실패 시 미확정으로 되돌린다. cloud_id·site_name·project_key·project_name은 그대로
    // 남겨 재동의 성공 시 자동 복원(IntegrationService)이 다시 쓸 수 있게 한다 — completeJiraProject처럼
    // external_ref를 통째로 교체하지 않는다.
    public void markJiraPendingProject() {
        Map<String, Object> reverted = new HashMap<>(externalRef);
        reverted.put(JIRA_STATUS, JIRA_STATUS_PENDING_PROJECT);
        this.externalRef = Map.copyOf(reverted);
    }

    // 사이트·프로젝트 선택 확정 — external_ref를 통째로 교체해 status를 자연히 제거한다
    public void completeJiraProject(String cloudId, String siteName, String projectKey, String projectName) {
        Map<String, Object> updated = new HashMap<>();
        updated.put(JIRA_CLOUD_ID, cloudId);
        updated.put(JIRA_SITE_NAME, siteName);
        updated.put(JIRA_PROJECT_KEY, projectKey);
        if (projectName != null && !projectName.isBlank()) {
            updated.put(JIRA_PROJECT_NAME, projectName);
        }
        // externalRef는 Map.copyOf로 불변이라 새 Map을 할당해야 Hibernate dirty checking이 필드 참조 변경을 잡는다
        this.externalRef = Map.copyOf(updated);
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
