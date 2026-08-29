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
    public static final String STATUS = "status";
    public static final String STATUS_PENDING_SELECTION = "pending_selection";
    // 중립 값 이전에 Jira가 쓰던 값 — 저장된 행 호환을 위해 읽기에서만 인정한다
    private static final String STATUS_LEGACY_PENDING_PROJECT = "pending_project";

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

    @Column(name = "incremental_enabled", nullable = false)
    private boolean incrementalEnabled = true;

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

    // OAuth로 붙는 연동의 공통 생성자 — provider가 늘어도 팩토리를 새로 만들 필요가 없다.
    // GitHub만 예외다(App installation 참조를 쓰고 자격증명은 github_installations에 캐시된다).
    public static Integration oauth(
            Project project,
            IntegrationProvider provider,
            Map<String, Object> externalRef,
            byte[] encryptedCredential
    ) {
        return new Integration(project, provider, Map.copyOf(externalRef), null, encryptedCredential);
    }

    // 선택 단계가 있는 provider는 동의 직후 대상을 아직 모르므로 토큰만 담은 pending 행으로 시작한다.
    // 사용자가 대상을 고르면 applyExternalRef로 확정한다.
    public static Integration pendingSelection(
            Project project,
            IntegrationProvider provider,
            byte[] encryptedCredential
    ) {
        return oauth(project, provider, Map.of(STATUS, STATUS_PENDING_SELECTION), encryptedCredential);
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

    // external_ref에 저장된 문자열 값 읽기. 키 이름은 provider가 정한다
    // (선택 단계가 있으면 SelectionStep이, 없으면 OAuthConnectFlow가 담아 넣은 키다).
    public String externalRefValue(String key) {
        Object value = externalRef.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /**
     * 아직 쓸 수 없는 연동인지 — 동의만 하고 대상을 고르지 않았거나, 토큰 갱신이 영구 실패해 되돌아온 경우.
     *
     * <p>구 Jira 전용 값(`pending_project`)도 pending으로 읽는다 — 이미 저장된 행이 있어
     * 데이터 마이그레이션 없이 중립 값으로 넘어가기 위함이다. 쓰기는 항상 중립 값으로 한다.</p>
     */
    public boolean isPendingSelection() {
        Object status = externalRef.get(STATUS);
        return STATUS_PENDING_SELECTION.equals(status) || STATUS_LEGACY_PENDING_PROJECT.equals(status);
    }

    // pending 행 재동의 시 토큰 교체 (토큰 갱신도 이 메서드를 재사용한다)
    public void updateCredential(byte[] encryptedCredential) {
        this.encryptedCredential = Arrays.copyOf(encryptedCredential, encryptedCredential.length);
    }

    // 무료 티어 연동에 증분 수집을 다시 허용하지 않는다 — 유료 전환 시에만 호출된다
    public void enableIncremental() {
        this.incrementalEnabled = true;
    }

    // 무료 티어 provider 연동 저장 시 호출 — 기본값(true)이라 PAID/무관 케이스는 건드릴 필요가 없다
    public void disableIncremental() {
        this.incrementalEnabled = false;
    }

    // 토큰 갱신 영구 실패 시 미확정으로 되돌린다. 이미 고른 값들은 그대로 남겨 재동의 성공 시
    // 자동 복원(IntegrationService)이 다시 쓸 수 있게 한다 — applyExternalRef처럼 통째로 교체하지 않는다.
    public void markPendingSelection() {
        Map<String, Object> reverted = new HashMap<>(externalRef);
        reverted.put(STATUS, STATUS_PENDING_SELECTION);
        this.externalRef = Map.copyOf(reverted);
    }

    // 수집 대상 확정 — external_ref를 통째로 교체해 pending status를 자연히 제거한다
    public void applyExternalRef(Map<String, Object> externalRef) {
        // externalRef는 Map.copyOf로 불변이라 새 Map을 할당해야 Hibernate dirty checking이 필드 참조 변경을 잡는다
        this.externalRef = Map.copyOf(externalRef);
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
