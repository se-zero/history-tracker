package com.history.pipeline_worker.collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProjectIntegrationRepositoryTest {

    private static final UUID INTEGRATION_ID = UUID.fromString("a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1");

    // null 인자(installationId 누락)면 jdbc를 타지 않고 빈 리스트로 조기 반환 — 여러 프로젝트가 매칭될
    // 수 있는 팬아웃 메서드라 반환 타입이 Optional에서 List로 바뀌었다.
    @Test
    void findGitHubWebhookIntegrations_returnsEmptyListWithoutRequiredPayloadFields() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        ProjectIntegrationRepository repository = new ProjectIntegrationRepository(jdbcTemplate, new ObjectMapper());

        List<ProjectIntegrationRepository.IntegrationRow> result =
                repository.findGitHubWebhookIntegrations(null, 123L, "acme/widget");

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
            return List.of(mapper.mapRow(resultSet(true), 0));
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
        assertThat(row.incrementalEnabled()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void rowMapper_readsFalseIncrementalEnabled() throws Exception {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)
        )).thenAnswer(invocation -> {
            RowMapper<ProjectIntegrationRepository.IntegrationRow> mapper = invocation.getArgument(2);
            return List.of(mapper.mapRow(resultSet(false), 0));
        });

        ProjectIntegrationRepository repository = new ProjectIntegrationRepository(jdbcTemplate, new ObjectMapper());

        List<ProjectIntegrationRepository.IntegrationRow> rows = repository.findAllByProjectId(UUID.randomUUID());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).incrementalEnabled()).isFalse();
    }

    @Test
    void integrationRow_sixArgConstructor_defaultsIncrementalEnabledToTrue() {
        UUID projectId = UUID.randomUUID();
        ProjectIntegrationRepository.IntegrationRow row = new ProjectIntegrationRepository.IntegrationRow(
                projectId, "github", Map.of(), null, null, null
        );
        assertThat(row.incrementalEnabled()).isTrue();
    }

    // 해제 뒤 재연결하면 (project_id, provider) UNIQUE 때문에 새 행·새 id가 생긴다 — 수집이 시작 때의 id를 들고 있어야 옛 실행을 가릴 수 있다.
    @Test
    @SuppressWarnings("unchecked")
    void rowMapper_readsIntegrationId() throws Exception {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)
        )).thenAnswer(invocation -> {
            RowMapper<ProjectIntegrationRepository.IntegrationRow> mapper = invocation.getArgument(2);
            return List.of(mapper.mapRow(resultSet(true), 0));
        });

        ProjectIntegrationRepository repository = new ProjectIntegrationRepository(jdbcTemplate, new ObjectMapper());

        List<ProjectIntegrationRepository.IntegrationRow> rows = repository.findAllByProjectId(UUID.randomUUID());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).id()).isEqualTo(INTEGRATION_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findAllByProjectId_selectsIntegrationId() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        ProjectIntegrationRepository repository = new ProjectIntegrationRepository(jdbcTemplate, new ObjectMapper());

        repository.findAllByProjectId(UUID.randomUUID());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        assertThat(selectList(sqlCaptor.getValue())).containsPattern("\\bi\\.id\\b");
    }

    @Test
    @SuppressWarnings("unchecked")
    void findGitHubWebhookIntegrations_selectsIntegrationId() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        ProjectIntegrationRepository repository = new ProjectIntegrationRepository(jdbcTemplate, new ObjectMapper());

        repository.findGitHubWebhookIntegrations(456L, 123L, "acme/widget");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        assertThat(selectList(sqlCaptor.getValue())).containsPattern("\\bi\\.id\\b");
    }

    @Test
    void existsById_queriesIntegrationsTableById() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(true);
        ProjectIntegrationRepository repository = new ProjectIntegrationRepository(jdbcTemplate, new ObjectMapper());

        boolean exists = repository.existsById(INTEGRATION_ID);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), paramsCaptor.capture(), eq(Boolean.class));

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("EXISTS");
        assertThat(sql).contains("FROM integrations");
        // "project_id = :id"에도 "id = :id"가 부분 문자열로 들어 있다 — 단어 경계로 integrations의 id 컬럼만 인정한다
        assertThat(sql).containsPattern("\\bid\\s*=\\s*:id\\b");
        assertThat(paramsCaptor.getValue().getValue("id")).isEqualTo(INTEGRATION_ID);
        assertThat(exists).isTrue();
    }

    @Test
    void existsById_missingRow_returnsFalse() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(false);
        ProjectIntegrationRepository repository = new ProjectIntegrationRepository(jdbcTemplate, new ObjectMapper());

        assertThat(repository.existsById(INTEGRATION_ID)).isFalse();
    }

    // "gi.id"(JOIN 조건)에도 "i.id"가 부분 문자열로 들어 있어 contains로는 SELECT 목록 검증이 안 된다 — SELECT~FROM 사이만 잘라 단어 경계로 본다.
    private String selectList(String sql) {
        return sql.substring(0, sql.indexOf("FROM"));
    }

    private ResultSet resultSet(boolean incrementalEnabled) throws Exception {
        UUID projectId = UUID.randomUUID();
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getObject("id", UUID.class)).thenReturn(INTEGRATION_ID);
        when(resultSet.getObject("project_id", UUID.class)).thenReturn(projectId);
        when(resultSet.getString("provider")).thenReturn("github");
        when(resultSet.getObject("external_ref"))
                .thenReturn("{\"repository_id\":12345,\"repository_full_name\":\"acme/widget\"}");
        when(resultSet.getBytes("encrypted_credential")).thenReturn(null);
        when(resultSet.getBytes("encrypted_installation_token")).thenReturn(new byte[] {1, 2, 3});
        when(resultSet.getTimestamp("installation_token_expires_at"))
                .thenReturn(java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        when(resultSet.getBoolean("incremental_enabled")).thenReturn(incrementalEnabled);
        return resultSet;
    }
}
