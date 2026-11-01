package com.history.pipeline_worker.collection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class ProjectIntegrationRepository {

    private static final TypeReference<Map<String, Object>> EXTERNAL_REF_TYPE = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RowMapper<IntegrationRow> rowMapper;

    public ProjectIntegrationRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = new IntegrationRowMapper(objectMapper);
    }

    public List<IntegrationRow> findGitHubWebhookIntegrations(
            Long installationId,
            Long repositoryId,
            String repositoryFullName
    ) {
        if (installationId == null || repositoryId == null || repositoryFullName == null || repositoryFullName.isBlank()) {
            return List.of();
        }

        String sql = """
                SELECT i.id,
                       i.project_id,
                       i.provider,
                       i.external_ref,
                       i.encrypted_credential,
                       gi.encrypted_installation_token,
                       gi.installation_token_expires_at,
                       i.incremental_enabled
                FROM integrations i
                JOIN github_installations gi ON gi.id = i.installation_id
                WHERE i.provider = 'github'
                  AND gi.installation_id = :installationId
                  AND i.external_ref->>'repository_id' = :repositoryId
                  AND i.external_ref->>'repository_full_name' = :repositoryFullName
                ORDER BY i.project_id
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("installationId", installationId)
                .addValue("repositoryId", String.valueOf(repositoryId))
                .addValue("repositoryFullName", repositoryFullName);

        return jdbcTemplate.query(sql, params, rowMapper);
    }

    public List<IntegrationRow> findAllByProjectId(UUID projectId) {
        String sql = """
                SELECT i.id,
                       i.project_id,
                       i.provider,
                       i.external_ref,
                       i.encrypted_credential,
                       gi.encrypted_installation_token,
                       gi.installation_token_expires_at,
                       i.incremental_enabled
                FROM integrations i
                LEFT JOIN github_installations gi ON gi.id = i.installation_id
                WHERE i.project_id = :projectId
                ORDER BY i.provider
                """;

        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("projectId", projectId),
                rowMapper
        );
    }

    // 수집이 시작 때 들고 있던 연동이 아직 남아 있는지 확인한다. 해제 뒤 재연결하면 같은
    // (project_id, provider)라도 새 행·새 id가 생기므로, project_id가 아니라 id로 가려야 옛 실행이 걸러진다.
    public boolean existsById(UUID id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM integrations WHERE id = :id)",
                new MapSqlParameterSource("id", id),
                Boolean.class
        ));
    }

    public record IntegrationRow(
            // 호환 생성자로 만든 행(테스트 fixture)은 null — DB에서 읽은 행은 항상 채워진다.
            UUID id,
            UUID projectId,
            String provider,
            Map<String, Object> externalRef,
            byte[] encryptedCredential,
            byte[] encryptedInstallationToken,
            Instant installationTokenExpiresAt,
            boolean incrementalEnabled
    ) {
        // id 도입 이전 호출부 호환용 — 여러 SourceCollector 테스트가 이 7-인자 형태로 IntegrationRow를
        // 직접 만든다. id 없는 행은 수집 범위(CollectionRunContext)를 열지 않는다.
        public IntegrationRow(
                UUID projectId,
                String provider,
                Map<String, Object> externalRef,
                byte[] encryptedCredential,
                byte[] encryptedInstallationToken,
                Instant installationTokenExpiresAt,
                boolean incrementalEnabled
        ) {
            this(null, projectId, provider, externalRef, encryptedCredential, encryptedInstallationToken,
                    installationTokenExpiresAt, incrementalEnabled);
        }

        // incrementalEnabled 도입 이전 호출부 호환용 — 여러 SourceCollector 테스트가 이 6-인자 형태로
        // IntegrationRow를 직접 만든다. DB 기본값(TRUE)과 같은 뜻으로 위임한다.
        public IntegrationRow(
                UUID projectId,
                String provider,
                Map<String, Object> externalRef,
                byte[] encryptedCredential,
                byte[] encryptedInstallationToken,
                Instant installationTokenExpiresAt
        ) {
            this(projectId, provider, externalRef, encryptedCredential, encryptedInstallationToken,
                    installationTokenExpiresAt, true);
        }
    }

    private static class IntegrationRowMapper implements RowMapper<IntegrationRow> {

        private final ObjectMapper objectMapper;

        private IntegrationRowMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public IntegrationRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new IntegrationRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("project_id", UUID.class),
                    rs.getString("provider"),
                    readExternalRef(rs.getObject("external_ref")),
                    rs.getBytes("encrypted_credential"),
                    rs.getBytes("encrypted_installation_token"),
                    readInstant(rs, "installation_token_expires_at"),
                    rs.getBoolean("incremental_enabled")
            );
        }

        private Map<String, Object> readExternalRef(Object value) throws SQLException {
            if (value == null) {
                return Map.of();
            }
            try {
                return objectMapper.readValue(value.toString(), EXTERNAL_REF_TYPE);
            } catch (Exception exception) {
                throw new SQLException("Failed to read integration external_ref.", exception);
            }
        }

        private Instant readInstant(ResultSet rs, String column) throws SQLException {
            var timestamp = rs.getTimestamp(column);
            return timestamp == null ? null : timestamp.toInstant();
        }
    }
}
