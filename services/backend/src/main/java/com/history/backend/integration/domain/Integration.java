package com.history.backend.integration.domain;

import java.time.Instant;
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

@Getter
@Entity
@Table(name = "integrations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Integration {

    public static final String GITHUB_REPOSITORY_ID = "repository_id";
    public static final String GITHUB_REPOSITORY_FULL_NAME = "repository_full_name";

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
            String repositoryFullName
    ) {
        return new Integration(
                project,
                IntegrationProvider.GITHUB,
                Map.of(
                        GITHUB_REPOSITORY_ID, repositoryId,
                        GITHUB_REPOSITORY_FULL_NAME, repositoryFullName
                ),
                installation,
                null
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
        this.encryptedCredential = encryptedCredential;
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
        Object repositoryFullName = externalRef.get(GITHUB_REPOSITORY_FULL_NAME);
        if (repositoryFullName instanceof String text && !text.isBlank()) {
            return text;
        }
        if (repositoryFullName == null) {
            throw new IllegalStateException("Missing GitHub repository_full_name.");
        }
        throw new IllegalStateException(
                "Unexpected GitHub repository_full_name type: " + repositoryFullName.getClass()
        );
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
