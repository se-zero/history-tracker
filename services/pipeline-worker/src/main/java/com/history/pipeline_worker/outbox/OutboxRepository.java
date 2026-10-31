package com.history.pipeline_worker.outbox;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 표 {@code ingest_outbox}에 대한 SQL 경계.
 *
 * <p>릴레이가 단일 인스턴스라는 전제라 {@code FOR UPDATE SKIP LOCKED}를 쓰지 않는다 —
 * 인스턴스를 늘리면 같은 행을 둘이 집어 중복 발행하므로 그때 다시 설계해야 한다.
 * 행의 수명(발행 완료 1일 뒤 청소)은 {@code OutboxCleanup}이 맡고 이 클래스는 삭제 수단만 제공한다.</p>
 */
@Repository
public class OutboxRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public OutboxRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insertAll(List<OutboxInsert> rows) {
        if (rows.isEmpty()) return 0;

        String sql = """
                INSERT INTO ingest_outbox (project_id, integration_id, source, payload)
                VALUES (:projectId, :integrationId, :source, :payload)
                """;

        // integrationId가 null일 수 있는데, PG JDBC는 타입 없는 null UUID의 SQL 타입을 추론하지 못해 OTHER로 명시한다
        SqlParameterSource[] batch = rows.stream()
                .map(row -> new MapSqlParameterSource()
                        .addValue("projectId", row.projectId())
                        .addValue("integrationId", row.integrationId(), Types.OTHER)
                        .addValue("source", row.source())
                        .addValue("payload", row.payload()))
                .toArray(SqlParameterSource[]::new);

        jdbcTemplate.batchUpdate(sql, batch);
        return rows.size();
    }

    public List<OutboxRow> fetchUnpublished(int limit) {
        String sql = """
                SELECT id, project_id, source, payload
                FROM ingest_outbox
                WHERE published_at IS NULL
                ORDER BY id
                LIMIT :limit
                """;

        return jdbcTemplate.query(sql, new MapSqlParameterSource("limit", limit), outboxRowMapper());
    }

    public int markPublished(List<Long> ids) {
        // IN () 은 SQL 문법 오류라 빈 목록은 DB를 부르지 않는다
        if (ids.isEmpty()) return 0;

        String sql = "UPDATE ingest_outbox SET published_at = now() WHERE id IN (:ids)";
        return jdbcTemplate.update(sql, new MapSqlParameterSource("ids", ids));
    }

    public int deletePublishedBefore(Instant cutoff) {
        String sql = """
                DELETE FROM ingest_outbox
                WHERE published_at IS NOT NULL
                  AND published_at < :cutoff
                """;

        // PG JDBC는 java.time.Instant의 SQL 타입을 추론하지 못하므로 timestamptz에 매핑되는 OffsetDateTime(UTC)으로 변환
        return jdbcTemplate.update(sql, new MapSqlParameterSource(
                "cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC)));
    }

    private RowMapper<OutboxRow> outboxRowMapper() {
        return (rs, rowNum) -> new OutboxRow(
                rs.getLong("id"),
                rs.getObject("project_id", UUID.class),
                rs.getString("source"),
                rs.getString("payload")
        );
    }

    public record OutboxInsert(UUID projectId, UUID integrationId, String source, String payload) {
    }

    public record OutboxRow(long id, UUID projectId, String source, String payload) {
    }
}
