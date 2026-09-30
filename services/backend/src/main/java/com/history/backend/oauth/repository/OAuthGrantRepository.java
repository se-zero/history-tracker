package com.history.backend.oauth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// 연결된 앱(OAuth grant) 조회·철회 — oauth2_authorization 계열 테이블은 Spring Authorization Server가
// 소유해 JPA 엔티티가 없고(ddl-auto: validate 대상 아님), 라이브러리의 OAuth2AuthorizationService에는
// 사용자별 조회·삭제 API가 없어 JdbcTemplate으로 직접 다룬다. SQL은 PostgreSQL·H2 공용이다.
@Repository
@RequiredArgsConstructor
public class OAuthGrantRepository {

    private final JdbcTemplate jdbcTemplate;

    // refresh가 유효한 행만 앱 단위로 묶는다 — 코드만 발급된 행·만료된 행은 "연결"이 아니다.
    // access 만료는 무관하다(refresh로 계속 쓸 수 있다)
    public List<OAuthGrantRow> findActiveByPrincipal(String principalName, Instant now) {
        return jdbcTemplate.query("""
                SELECT registered_client_id,
                       MIN(COALESCE(authorization_code_issued_at, access_token_issued_at)) AS granted_at,
                       MAX(access_token_issued_at) AS last_used_at
                FROM oauth2_authorization
                WHERE principal_name = ?
                  AND refresh_token_value IS NOT NULL
                  AND refresh_token_expires_at > ?
                GROUP BY registered_client_id
                ORDER BY granted_at ASC
                """,
                (rs, rowNum) -> new OAuthGrantRow(
                        rs.getString("registered_client_id"),
                        toInstant(rs.getTimestamp("granted_at")),
                        toInstant(rs.getTimestamp("last_used_at"))),
                principalName, Timestamp.from(now));
    }

    // 보안: 경로의 앱 id는 사용자마다 다르지 않다. principal_name 조건이 빠지면 다른 사용자의
    // 연결까지 끊게 되므로 두 테이블 모두 (사용자, 앱) 조건을 함께 건다.
    // 호출부 서비스의 트랜잭션 안에서 실행돼야 두 삭제가 함께 커밋된다.
    public int deleteByPrincipalAndClient(String principalName, String registeredClientId) {
        jdbcTemplate.update(
                "DELETE FROM oauth2_authorization_consent WHERE principal_name = ? AND registered_client_id = ?",
                principalName, registeredClientId);
        return jdbcTemplate.update(
                "DELETE FROM oauth2_authorization WHERE principal_name = ? AND registered_client_id = ?",
                principalName, registeredClientId);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
