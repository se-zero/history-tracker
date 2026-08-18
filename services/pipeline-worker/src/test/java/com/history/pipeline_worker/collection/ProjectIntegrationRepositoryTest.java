package com.history.pipeline_worker.collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProjectIntegrationRepositoryTest {

    @Test
    void findGitHubWebhookIntegration_returnsEmptyWithoutRequiredPayloadFields() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        ProjectIntegrationRepository repository = new ProjectIntegrationRepository(jdbcTemplate, new ObjectMapper());

        Optional<ProjectIntegrationRepository.IntegrationRow> result =
                repository.findGitHubWebhookIntegration(null, 123L, "acme/widget");

        assertThat(result).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rowMapper_readsExternalRefAndNullableInstallationTokenFields() throws Exception {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)
        )).thenAnswer(invocation -> {
            RowMapper<ProjectIntegrationRepository.IntegrationRow> mapper = invocation.getArgument(2);
            return List.of(mapper.mapRow(resultSet(), 0));
        });

        ProjectIntegrationRepository repository = new ProjectIntegrationRepository(jdbcTemplate, new ObjectMapper());

        List<ProjectIntegrationRepository.IntegrationRow> rows = repository.findAllByProjectId(UUID.randomUUID());

        assertThat(rows).hasSize(1);
        ProjectIntegrationRepository.IntegrationRow row = rows.get(0);
        assertThat(row.provider()).isEqualTo("github");
        assertThat(row.externalRef())
                .containsEntry("repository_full_name", "acme/widget")
                .containsEntry("repository_id", 12345);
        assertThat(row.encryptedCredential()).isNull();
        assertThat(row.encryptedInstallationToken()).containsExactly(1, 2, 3);
        assertThat(row.installationTokenExpiresAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    private ResultSet resultSet() throws Exception {
        UUID projectId = UUID.randomUUID();
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getObject("project_id", UUID.class)).thenReturn(projectId);
        when(resultSet.getString("provider")).thenReturn("github");
        when(resultSet.getObject("external_ref"))
                .thenReturn("{\"repository_id\":12345,\"repository_full_name\":\"acme/widget\"}");
        when(resultSet.getBytes("encrypted_credential")).thenReturn(null);
        when(resultSet.getBytes("encrypted_installation_token")).thenReturn(new byte[] {1, 2, 3});
        when(resultSet.getTimestamp("installation_token_expires_at"))
                .thenReturn(java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        return resultSet;
    }
}
