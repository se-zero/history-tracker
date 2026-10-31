package com.history.pipeline_worker.outbox;

import com.history.pipeline_worker.outbox.OutboxRepository.OutboxInsert;
import com.history.pipeline_worker.outbox.OutboxRepository.OutboxRow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OutboxRepositoryTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INTEGRATION_ID = UUID.fromString("a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1");

    @Test
    void insertAll_batchesInsertWithNamedParameters() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenReturn(new int[]{1, 1});
        OutboxRepository repository = new OutboxRepository(jdbcTemplate);

        int inserted = repository.insertAll(List.of(
                new OutboxInsert(PROJECT_ID, INTEGRATION_ID, "GITHUB", "{\"a\":1}"),
                new OutboxInsert(PROJECT_ID, INTEGRATION_ID, "JIRA", "{\"b\":2}")));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource[]> batchCaptor = ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(jdbcTemplate).batchUpdate(sqlCaptor.capture(), batchCaptor.capture());

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("INSERT INTO ingest_outbox");
        assertThat(sql).contains("project_id", "integration_id", "source", "payload");
        assertThat(sql).contains(":projectId", ":integrationId", ":source", ":payload");

        SqlParameterSource[] batch = batchCaptor.getValue();
        assertThat(batch).hasSize(2);
        assertThat(batch[0].getValue("projectId")).isEqualTo(PROJECT_ID);
        assertThat(batch[0].getValue("integrationId")).isEqualTo(INTEGRATION_ID);
        assertThat(batch[0].getValue("source")).isEqualTo("GITHUB");
        assertThat(batch[0].getValue("payload")).isEqualTo("{\"a\":1}");
        assertThat(batch[1].getValue("source")).isEqualTo("JIRA");
        assertThat(batch[1].getValue("payload")).isEqualTo("{\"b\":2}");
        assertThat(inserted).isEqualTo(2);
    }

    @Test
    void insertAll_nullIntegrationId_bindsNullWithOtherSqlType() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenReturn(new int[]{1});
        OutboxRepository repository = new OutboxRepository(jdbcTemplate);

        repository.insertAll(List.of(new OutboxInsert(PROJECT_ID, null, "SLACK", "{}")));

        ArgumentCaptor<SqlParameterSource[]> batchCaptor = ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(jdbcTemplate).batchUpdate(anyString(), batchCaptor.capture());
        SqlParameterSource row = batchCaptor.getValue()[0];
        assertThat(row.hasValue("integrationId")).isTrue();
        assertThat(row.getValue("integrationId")).isNull();
        // PG JDBC는 타입 없는 null을 추론하지 못하므로 OTHER로 명시해야 한다
        assertThat(row.getSqlType("integrationId")).isEqualTo(Types.OTHER);
    }

    @Test
    void insertAll_emptyList_returnsZeroWithoutDbCall() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        OutboxRepository repository = new OutboxRepository(jdbcTemplate);

        int inserted = repository.insertAll(List.of());

        assertThat(inserted).isZero();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void fetchUnpublished_selectsUnpublishedInIdOrderWithLimit() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        OutboxRow expected = new OutboxRow(7L, PROJECT_ID, "GITHUB", "{}");
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(expected));
        OutboxRepository repository = new OutboxRepository(jdbcTemplate);

        List<OutboxRow> rows = repository.fetchUnpublished(200);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), paramsCaptor.capture(), any(RowMapper.class));

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("FROM ingest_outbox");
        assertThat(sql).contains("published_at IS NULL");
        assertThat(sql).contains("ORDER BY id");
        assertThat(sql).contains("LIMIT :limit");
        assertThat(paramsCaptor.getValue().getValue("limit")).isEqualTo(200);
        assertThat(rows).containsExactly(expected);
    }

    @Test
    @SuppressWarnings("unchecked")
    void fetchUnpublished_rowMapperMapsColumnsToOutboxRow() throws SQLException {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        OutboxRepository repository = new OutboxRepository(jdbcTemplate);
        repository.fetchUnpublished(10);

        ArgumentCaptor<RowMapper<OutboxRow>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        verify(jdbcTemplate).query(anyString(), any(SqlParameterSource.class), mapperCaptor.capture());
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(42L);
        when(rs.getObject("project_id", UUID.class)).thenReturn(PROJECT_ID);
        when(rs.getString("source")).thenReturn("JIRA");
        when(rs.getString("payload")).thenReturn("{\"k\":\"v\"}");

        OutboxRow row = mapperCaptor.getValue().mapRow(rs, 0);

        assertThat(row).isEqualTo(new OutboxRow(42L, PROJECT_ID, "JIRA", "{\"k\":\"v\"}"));
    }

    @Test
    void markPublished_updatesPublishedAtForGivenIds() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(3);
        OutboxRepository repository = new OutboxRepository(jdbcTemplate);
        List<Long> ids = List.of(1L, 2L, 3L);

        int marked = repository.markPublished(ids);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("UPDATE ingest_outbox");
        assertThat(sql).contains("published_at = now()");
        assertThat(sql).contains("id IN (:ids)");
        assertThat(paramsCaptor.getValue().getValue("ids")).isEqualTo(ids);
        assertThat(marked).isEqualTo(3);
    }

    @Test
    void markPublished_emptyIds_returnsZeroWithoutDbCall() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        OutboxRepository repository = new OutboxRepository(jdbcTemplate);

        int marked = repository.markPublished(List.of());

        // IN () 은 SQL 문법 오류라 빈 목록은 DB를 부르지 않는다
        assertThat(marked).isZero();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void deletePublishedBefore_deletesOnlyPublishedRowsOlderThanCutoff() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(5);
        OutboxRepository repository = new OutboxRepository(jdbcTemplate);
        Instant cutoff = Instant.parse("2026-10-30T00:00:00Z");

        int deleted = repository.deletePublishedBefore(cutoff);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("DELETE FROM ingest_outbox");
        assertThat(sql).contains("published_at IS NOT NULL");
        assertThat(sql).contains("published_at < :cutoff");
        // PG JDBC는 Instant를 추론하지 못하므로 OffsetDateTime(UTC)으로 바인딩
        assertThat(paramsCaptor.getValue().getValue("cutoff"))
                .isEqualTo(OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
        assertThat(deleted).isEqualTo(5);
    }
}
