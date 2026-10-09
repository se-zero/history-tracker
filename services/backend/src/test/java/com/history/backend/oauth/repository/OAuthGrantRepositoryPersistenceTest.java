package com.history.backend.oauth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration")
@Import(OAuthGrantRepository.class)
@DisplayName("OAuthGrantRepository: oauth2_authorization 연결 목록·철회 SQL")
class OAuthGrantRepositoryPersistenceTest {

    // PostgreSQL timestamptz 정밀도(마이크로초)에 맞춘 고정 시각
    private static final Instant NOW = Instant.parse("2026-09-20T03:12:00Z").truncatedTo(ChronoUnit.MICROS);
    private static final String PRINCIPAL = "fdd87bd0-3751-4336-a2db-c05d931c4f50";
    private static final String OTHER_PRINCIPAL = "801db2d0-f3dd-4dfc-ae2a-8ea12678ba59";
    private static final String CLIENT_A = "client-a";
    private static final String CLIENT_B = "client-b";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OAuthGrantRepositoryPersistenceTest::postgresJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static String postgresJdbcUrl() {
        return postgres.getJdbcUrl() + "&stringtype=unspecified";
    }

    @Autowired
    private OAuthGrantRepository oAuthGrantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("코드만 발급되고 토큰이 없는 행은 목록에서 제외")
    void findActiveByPrincipalExcludesCodeOnlyRows() {
        insertAuthorization(PRINCIPAL, CLIENT_A, NOW.minusSeconds(60), null, null, null);

        assertThat(oAuthGrantRepository.findActiveByPrincipal(PRINCIPAL, NOW)).isEmpty();
    }

    @Test
    @DisplayName("refresh 만료 시각이 있어도 refresh 값이 없는 행은 제외")
    void findActiveByPrincipalExcludesRowsWithoutRefreshTokenValue() {
        jdbcTemplate.update("""
                INSERT INTO oauth2_authorization
                    (id, registered_client_id, principal_name, authorization_grant_type, refresh_token_expires_at)
                VALUES (?, ?, ?, 'authorization_code', ?)
                """, UUID.randomUUID().toString(), CLIENT_A, PRINCIPAL, ts(NOW.plus(Duration.ofDays(1))));

        assertThat(oAuthGrantRepository.findActiveByPrincipal(PRINCIPAL, NOW)).isEmpty();
    }

    @Test
    @DisplayName("refresh가 만료된 행은 제외")
    void findActiveByPrincipalExcludesExpiredRefreshToken() {
        insertAuthorization(PRINCIPAL, CLIENT_A, NOW.minus(Duration.ofDays(40)), NOW.minus(Duration.ofDays(40)),
                "refresh-expired", NOW.minusSeconds(1));

        assertThat(oAuthGrantRepository.findActiveByPrincipal(PRINCIPAL, NOW)).isEmpty();
    }

    @Test
    @DisplayName("refresh 만료 시각이 now와 같으면 제외(경계)")
    void findActiveByPrincipalExcludesRefreshTokenExpiringExactlyNow() {
        insertAuthorization(PRINCIPAL, CLIENT_A, NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(30)),
                "refresh-boundary", NOW);

        assertThat(oAuthGrantRepository.findActiveByPrincipal(PRINCIPAL, NOW)).isEmpty();
    }

    @Test
    @DisplayName("access는 만료됐어도 refresh가 유효하면 포함")
    void findActiveByPrincipalIncludesRowWithExpiredAccessAndValidRefresh() {
        Instant codeIssuedAt = NOW.minus(Duration.ofDays(2));
        Instant accessIssuedAt = NOW.minus(Duration.ofDays(2)).plusSeconds(5);
        insertAuthorization(PRINCIPAL, CLIENT_A, codeIssuedAt, accessIssuedAt,
                "refresh-valid", NOW.plus(Duration.ofDays(28)));

        List<OAuthGrantRow> rows = oAuthGrantRepository.findActiveByPrincipal(PRINCIPAL, NOW);

        assertThat(rows).containsExactly(new OAuthGrantRow(CLIENT_A, codeIssuedAt, accessIssuedAt));
    }

    @Test
    @DisplayName("같은 앱의 행 여러 개는 1항목으로 묶고 grantedAt은 가장 이른 코드 발급, lastUsedAt은 가장 늦은 access 발급")
    void findActiveByPrincipalAggregatesRowsOfSameClient() {
        Instant firstCodeAt = NOW.minus(Duration.ofDays(10));
        Instant firstAccessAt = firstCodeAt.plusSeconds(5);
        Instant secondCodeAt = NOW.minus(Duration.ofDays(1));
        Instant secondAccessAt = secondCodeAt.plusSeconds(5);
        insertAuthorization(PRINCIPAL, CLIENT_A, firstCodeAt, firstAccessAt,
                "refresh-1", NOW.plus(Duration.ofDays(20)));
        insertAuthorization(PRINCIPAL, CLIENT_A, secondCodeAt, secondAccessAt,
                "refresh-2", NOW.plus(Duration.ofDays(29)));

        List<OAuthGrantRow> rows = oAuthGrantRepository.findActiveByPrincipal(PRINCIPAL, NOW);

        assertThat(rows).containsExactly(new OAuthGrantRow(CLIENT_A, firstCodeAt, secondAccessAt));
    }

    @Test
    @DisplayName("같은 앱에 만료된 행이 섞여 있어도 유효한 행만 집계 — 만료 행의 이른 시각이 grantedAt을 끌어내리지 않는다")
    void findActiveByPrincipalAggregatesOnlyActiveRowsOfSameClient() {
        Instant expiredCodeAt = NOW.minus(Duration.ofDays(60));
        Instant expiredAccessAt = expiredCodeAt.plusSeconds(5);
        Instant activeCodeAt = NOW.minus(Duration.ofDays(3));
        Instant activeAccessAt = activeCodeAt.plusSeconds(5);
        insertAuthorization(PRINCIPAL, CLIENT_A, expiredCodeAt, expiredAccessAt,
                "refresh-expired", NOW.minus(Duration.ofDays(30)));
        insertAuthorization(PRINCIPAL, CLIENT_A, activeCodeAt, activeAccessAt,
                "refresh-active", NOW.plus(Duration.ofDays(27)));

        List<OAuthGrantRow> rows = oAuthGrantRepository.findActiveByPrincipal(PRINCIPAL, NOW);

        assertThat(rows).containsExactly(new OAuthGrantRow(CLIENT_A, activeCodeAt, activeAccessAt));
    }

    @Test
    @DisplayName("authorization_code_issued_at이 null이면 grantedAt은 access_token_issued_at으로 대체")
    void findActiveByPrincipalFallsBackToAccessTokenIssuedAtForGrantedAt() {
        Instant accessIssuedAt = NOW.minus(Duration.ofDays(1));
        insertAuthorization(PRINCIPAL, CLIENT_A, null, accessIssuedAt,
                "refresh-no-code", NOW.plus(Duration.ofDays(29)));

        List<OAuthGrantRow> rows = oAuthGrantRepository.findActiveByPrincipal(PRINCIPAL, NOW);

        assertThat(rows).containsExactly(new OAuthGrantRow(CLIENT_A, accessIssuedAt, accessIssuedAt));
    }

    @Test
    @DisplayName("앱이 둘이면 2항목, grantedAt 오름차순")
    void findActiveByPrincipalOrdersByGrantedAtAscending() {
        Instant earlierCodeAt = NOW.minus(Duration.ofDays(5));
        Instant laterCodeAt = NOW.minus(Duration.ofDays(1));
        // 삽입 순서를 정렬과 반대로 둬 ORDER BY 없이는 통과하지 못하게 한다
        insertAuthorization(PRINCIPAL, CLIENT_B, laterCodeAt, laterCodeAt.plusSeconds(5),
                "refresh-b", NOW.plus(Duration.ofDays(29)));
        insertAuthorization(PRINCIPAL, CLIENT_A, earlierCodeAt, earlierCodeAt.plusSeconds(5),
                "refresh-a", NOW.plus(Duration.ofDays(25)));

        List<OAuthGrantRow> rows = oAuthGrantRepository.findActiveByPrincipal(PRINCIPAL, NOW);

        assertThat(rows).extracting(OAuthGrantRow::registeredClientId).containsExactly(CLIENT_A, CLIENT_B);
        assertThat(rows).extracting(OAuthGrantRow::grantedAt).containsExactly(earlierCodeAt, laterCodeAt);
    }

    @Test
    @DisplayName("다른 사용자(principal)의 행은 제외")
    void findActiveByPrincipalExcludesOtherPrincipals() {
        insertAuthorization(OTHER_PRINCIPAL, CLIENT_A, NOW.minusSeconds(60), NOW.minusSeconds(55),
                "refresh-other", NOW.plus(Duration.ofDays(29)));

        assertThat(oAuthGrantRepository.findActiveByPrincipal(PRINCIPAL, NOW)).isEmpty();
    }

    @Test
    @DisplayName("철회는 대상 (사용자, 앱)의 authorization 행만 지우고 지운 행 수를 반환")
    void deleteByPrincipalAndClientDeletesOnlyTargetRows() {
        String targetRow1 = insertAuthorization(PRINCIPAL, CLIENT_A, NOW.minusSeconds(120), NOW.minusSeconds(115),
                "refresh-target-1", NOW.plus(Duration.ofDays(29)));
        String targetRow2 = insertAuthorization(PRINCIPAL, CLIENT_A, NOW.minusSeconds(60), NOW.minusSeconds(55),
                "refresh-target-2", NOW.plus(Duration.ofDays(29)));
        String sameUserOtherClient = insertAuthorization(PRINCIPAL, CLIENT_B, NOW.minusSeconds(60), NOW.minusSeconds(55),
                "refresh-other-client", NOW.plus(Duration.ofDays(29)));
        String otherUserSameClient = insertAuthorization(OTHER_PRINCIPAL, CLIENT_A, NOW.minusSeconds(60), NOW.minusSeconds(55),
                "refresh-other-user", NOW.plus(Duration.ofDays(29)));

        int deleted = oAuthGrantRepository.deleteByPrincipalAndClient(PRINCIPAL, CLIENT_A);

        assertThat(deleted).isEqualTo(2);
        assertThat(remainingAuthorizationIds())
                .containsExactlyInAnyOrder(sameUserOtherClient, otherUserSameClient)
                .doesNotContain(targetRow1, targetRow2);
    }

    @Test
    @DisplayName("철회는 oauth2_authorization_consent의 해당 (사용자, 앱) 행도 지우고 나머지는 남긴다")
    void deleteByPrincipalAndClientDeletesOnlyTargetConsentRow() {
        insertAuthorization(PRINCIPAL, CLIENT_A, NOW.minusSeconds(60), NOW.minusSeconds(55),
                "refresh-target", NOW.plus(Duration.ofDays(29)));
        insertConsent(CLIENT_A, PRINCIPAL);
        insertConsent(CLIENT_B, PRINCIPAL);
        insertConsent(CLIENT_A, OTHER_PRINCIPAL);

        oAuthGrantRepository.deleteByPrincipalAndClient(PRINCIPAL, CLIENT_A);

        assertThat(jdbcTemplate.queryForList(
                "SELECT registered_client_id || '/' || principal_name FROM oauth2_authorization_consent", String.class))
                .containsExactlyInAnyOrder(CLIENT_B + "/" + PRINCIPAL, CLIENT_A + "/" + OTHER_PRINCIPAL);
    }

    @Test
    @DisplayName("지울 행이 없으면 0 반환")
    void deleteByPrincipalAndClientReturnsZeroWhenNothingToDelete() {
        insertAuthorization(OTHER_PRINCIPAL, CLIENT_A, NOW.minusSeconds(60), NOW.minusSeconds(55),
                "refresh-other-user", NOW.plus(Duration.ofDays(29)));

        int deleted = oAuthGrantRepository.deleteByPrincipalAndClient(PRINCIPAL, CLIENT_A);

        assertThat(deleted).isZero();
        assertThat(remainingAuthorizationIds()).hasSize(1);
    }

    @Test
    @DisplayName("코드만 있고 코드가 만료된 행은 삭제")
    void deleteExpiredDeletesCodeOnlyRowWithExpiredCode() {
        String id = insertWithExpiry(PRINCIPAL, CLIENT_A, NOW.minusSeconds(1), null, null);

        int deleted = oAuthGrantRepository.deleteExpired(NOW);

        assertThat(deleted).isEqualTo(1);
        assertThat(remainingAuthorizationIds()).doesNotContain(id);
    }

    @Test
    @DisplayName("코드만 있고 아직 유효한 행은 유지")
    void deleteExpiredKeepsCodeOnlyRowWithValidCode() {
        String id = insertWithExpiry(PRINCIPAL, CLIENT_A, NOW.plusSeconds(60), null, null);

        int deleted = oAuthGrantRepository.deleteExpired(NOW);

        assertThat(deleted).isZero();
        assertThat(remainingAuthorizationIds()).containsExactly(id);
    }

    @Test
    @DisplayName("access·코드는 만료됐어도 refresh가 유효하면 유지 — 살아 있는 연결을 지우면 안 된다")
    void deleteExpiredKeepsRowWithExpiredAccessAndCodeButValidRefresh() {
        String id = insertWithExpiry(PRINCIPAL, CLIENT_A,
                NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(2)).plusSeconds(3600),
                NOW.plus(Duration.ofDays(28)));

        int deleted = oAuthGrantRepository.deleteExpired(NOW);

        assertThat(deleted).isZero();
        assertThat(remainingAuthorizationIds()).containsExactly(id);
    }

    @Test
    @DisplayName("refresh가 만료된 행은 삭제")
    void deleteExpiredDeletesRowWithExpiredRefresh() {
        String id = insertWithExpiry(PRINCIPAL, CLIENT_A,
                NOW.minus(Duration.ofDays(40)), NOW.minus(Duration.ofDays(40)).plusSeconds(3600),
                NOW.minusSeconds(1));

        int deleted = oAuthGrantRepository.deleteExpired(NOW);

        assertThat(deleted).isEqualTo(1);
        assertThat(remainingAuthorizationIds()).doesNotContain(id);
    }

    @Test
    @DisplayName("refresh 없이 access만 있고 만료된 행은 삭제")
    void deleteExpiredDeletesAccessOnlyRowWithExpiredAccess() {
        String id = insertWithExpiry(PRINCIPAL, CLIENT_A, NOW.minusSeconds(7200), NOW.minusSeconds(1), null);

        int deleted = oAuthGrantRepository.deleteExpired(NOW);

        assertThat(deleted).isEqualTo(1);
        assertThat(remainingAuthorizationIds()).doesNotContain(id);
    }

    @Test
    @DisplayName("refresh 없이 access만 있고 유효하면 유지 — 코드가 만료됐어도 access 기준")
    void deleteExpiredKeepsAccessOnlyRowWithValidAccess() {
        String id = insertWithExpiry(PRINCIPAL, CLIENT_A, NOW.minusSeconds(600), NOW.plusSeconds(3000), null);

        int deleted = oAuthGrantRepository.deleteExpired(NOW);

        assertThat(deleted).isZero();
        assertThat(remainingAuthorizationIds()).containsExactly(id);
    }

    @Test
    @DisplayName("기준 컬럼이 now와 같으면 유지(경계) — refresh·access·코드 각각")
    void deleteExpiredKeepsRowsWhoseDecidingColumnEqualsNow() {
        insertWithExpiry(PRINCIPAL, CLIENT_A, NOW.minusSeconds(600), NOW.minusSeconds(300), NOW);
        insertWithExpiry(PRINCIPAL, CLIENT_B, NOW.minusSeconds(600), NOW, null);
        insertWithExpiry(OTHER_PRINCIPAL, CLIENT_A, NOW, null, null);

        int deleted = oAuthGrantRepository.deleteExpired(NOW);

        assertThat(deleted).isZero();
        assertThat(remainingAuthorizationIds()).hasSize(3);
    }

    @Test
    @DisplayName("만료 시각이 전부 null인 행은 유지")
    void deleteExpiredKeepsRowWithAllExpiryColumnsNull() {
        String id = insertWithExpiry(PRINCIPAL, CLIENT_A, null, null, null);

        int deleted = oAuthGrantRepository.deleteExpired(NOW);

        assertThat(deleted).isZero();
        assertThat(remainingAuthorizationIds()).containsExactly(id);
    }

    @Test
    @DisplayName("여러 사용자·앱이 섞여 있어도 만료된 행만 지우고 지운 행 수를 반환")
    void deleteExpiredDeletesOnlyExpiredRowsAcrossPrincipalsAndClients() {
        String expiredCodeOnly = insertWithExpiry(PRINCIPAL, CLIENT_A, NOW.minusSeconds(1), null, null);
        String expiredRefresh = insertWithExpiry(OTHER_PRINCIPAL, CLIENT_A,
                NOW.minus(Duration.ofDays(40)), NOW.minus(Duration.ofDays(40)), NOW.minus(Duration.ofDays(10)));
        String expiredAccessOnly = insertWithExpiry(OTHER_PRINCIPAL, CLIENT_B,
                NOW.minusSeconds(7200), NOW.minusSeconds(3600), null);
        String liveRefresh = insertWithExpiry(PRINCIPAL, CLIENT_B,
                NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(28)));
        String liveCodeOnly = insertWithExpiry(OTHER_PRINCIPAL, CLIENT_A, NOW.plusSeconds(120), null, null);

        int deleted = oAuthGrantRepository.deleteExpired(NOW);

        assertThat(deleted).isEqualTo(3);
        assertThat(remainingAuthorizationIds())
                .containsExactlyInAnyOrder(liveRefresh, liveCodeOnly)
                .doesNotContain(expiredCodeOnly, expiredRefresh, expiredAccessOnly);
    }

    @Test
    @DisplayName("만료 행을 지워도 oauth2_authorization_consent는 건드리지 않는다")
    void deleteExpiredLeavesConsentRows() {
        insertWithExpiry(PRINCIPAL, CLIENT_A, NOW.minusSeconds(1), null, null);
        insertConsent(CLIENT_A, PRINCIPAL);
        insertConsent(CLIENT_B, OTHER_PRINCIPAL);

        oAuthGrantRepository.deleteExpired(NOW);

        assertThat(remainingConsentKeys())
                .containsExactlyInAnyOrder(CLIENT_A + "/" + PRINCIPAL, CLIENT_B + "/" + OTHER_PRINCIPAL);
    }

    @Test
    @DisplayName("사용자 전체 삭제는 그 사용자의 모든 앱 행(유효·만료·코드만)을 지우고 다른 사용자 행은 남기며 지운 행 수를 반환")
    void deleteByPrincipalDeletesAllRowsOfPrincipalOnly() {
        String live = insertAuthorization(PRINCIPAL, CLIENT_A, NOW.minusSeconds(60), NOW.minusSeconds(55),
                "refresh-live", NOW.plus(Duration.ofDays(29)));
        String expired = insertAuthorization(PRINCIPAL, CLIENT_B, NOW.minus(Duration.ofDays(60)),
                NOW.minus(Duration.ofDays(60)), "refresh-expired", NOW.minus(Duration.ofDays(30)));
        String codeOnly = insertWithExpiry(PRINCIPAL, CLIENT_A, NOW.plusSeconds(120), null, null);
        String otherUser = insertAuthorization(OTHER_PRINCIPAL, CLIENT_A, NOW.minusSeconds(60), NOW.minusSeconds(55),
                "refresh-other", NOW.plus(Duration.ofDays(29)));

        int deleted = oAuthGrantRepository.deleteByPrincipal(PRINCIPAL);

        assertThat(deleted).isEqualTo(3);
        assertThat(remainingAuthorizationIds())
                .containsExactly(otherUser)
                .doesNotContain(live, expired, codeOnly);
    }

    @Test
    @DisplayName("사용자 전체 삭제는 oauth2_authorization_consent의 그 사용자 행 전부를 지우고 다른 사용자 행은 남긴다")
    void deleteByPrincipalDeletesAllConsentRowsOfPrincipalOnly() {
        insertAuthorization(PRINCIPAL, CLIENT_A, NOW.minusSeconds(60), NOW.minusSeconds(55),
                "refresh-live", NOW.plus(Duration.ofDays(29)));
        insertConsent(CLIENT_A, PRINCIPAL);
        insertConsent(CLIENT_B, PRINCIPAL);
        insertConsent(CLIENT_A, OTHER_PRINCIPAL);

        oAuthGrantRepository.deleteByPrincipal(PRINCIPAL);

        assertThat(remainingConsentKeys()).containsExactly(CLIENT_A + "/" + OTHER_PRINCIPAL);
    }

    @Test
    @DisplayName("사용자 전체 삭제는 authorization 행이 없고 consent만 있어도 consent를 지운다")
    void deleteByPrincipalDeletesConsentRowsEvenWithoutAuthorizationRows() {
        insertConsent(CLIENT_A, PRINCIPAL);

        int deleted = oAuthGrantRepository.deleteByPrincipal(PRINCIPAL);

        assertThat(deleted).isZero();
        assertThat(remainingConsentKeys()).isEmpty();
    }

    @Test
    @DisplayName("사용자 전체 삭제는 지울 행이 없으면 0 반환하고 다른 사용자 행은 그대로")
    void deleteByPrincipalReturnsZeroWhenNothingToDelete() {
        insertAuthorization(OTHER_PRINCIPAL, CLIENT_A, NOW.minusSeconds(60), NOW.minusSeconds(55),
                "refresh-other", NOW.plus(Duration.ofDays(29)));

        int deleted = oAuthGrantRepository.deleteByPrincipal(PRINCIPAL);

        assertThat(deleted).isZero();
        assertThat(remainingAuthorizationIds()).hasSize(1);
    }

    @Test
    @DisplayName("기준 시각보다 오래됐고 연결이 없는 앱은 삭제하고 지운 행 수를 반환")
    void deleteUnusedClientsDeletesOldClientWithoutAuthorizations() {
        insertRegisteredClient("id-old", "client-old", NOW.minus(Duration.ofDays(1)));

        int deleted = oAuthGrantRepository.deleteUnusedClients(NOW);

        assertThat(deleted).isEqualTo(1);
        assertThat(remainingClientIds()).isEmpty();
    }

    @Test
    @DisplayName("기준 시각보다 최근에 등록된 앱은 유지")
    void deleteUnusedClientsKeepsClientIssuedAfterCutoff() {
        insertRegisteredClient("id-recent", "client-recent", NOW.plusSeconds(1));

        int deleted = oAuthGrantRepository.deleteUnusedClients(NOW);

        assertThat(deleted).isZero();
        assertThat(remainingClientIds()).containsExactly("id-recent");
    }

    @Test
    @DisplayName("등록 시각이 기준 시각과 정확히 같으면 유지(경계)")
    void deleteUnusedClientsKeepsClientIssuedExactlyAtCutoff() {
        insertRegisteredClient("id-boundary", "client-boundary", NOW);

        int deleted = oAuthGrantRepository.deleteUnusedClients(NOW);

        assertThat(deleted).isZero();
        assertThat(remainingClientIds()).containsExactly("id-boundary");
    }

    @Test
    @DisplayName("오래됐어도 살아 있는 연결이 있는 앱은 유지 — 지우면 입구 검증이 앱 행을 못 읽는다")
    void deleteUnusedClientsKeepsOldClientWithLiveAuthorization() {
        insertRegisteredClient("id-live", "client-live", NOW.minus(Duration.ofDays(10)));
        insertAuthorization(PRINCIPAL, "id-live", NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(2)),
                "refresh-live", NOW.plus(Duration.ofDays(28)));

        int deleted = oAuthGrantRepository.deleteUnusedClients(NOW);

        assertThat(deleted).isZero();
        assertThat(remainingClientIds()).containsExactly("id-live");
    }

    @Test
    @DisplayName("만료됐지만 아직 지워지지 않은 연결만 있어도 앱은 유지 — 만료 행 정리는 deleteExpired의 몫")
    void deleteUnusedClientsKeepsOldClientWithExpiredButUnpurgedAuthorization() {
        insertRegisteredClient("id-expired", "client-expired", NOW.minus(Duration.ofDays(60)));
        insertAuthorization(PRINCIPAL, "id-expired", NOW.minus(Duration.ofDays(60)), NOW.minus(Duration.ofDays(60)),
                "refresh-expired", NOW.minus(Duration.ofDays(30)));

        int deleted = oAuthGrantRepository.deleteUnusedClients(NOW);

        assertThat(deleted).isZero();
        assertThat(remainingClientIds()).containsExactly("id-expired");
    }

    @Test
    @DisplayName("연결이 없는 앱 A와 연결이 있는 앱 B가 섞여 있으면 A만 삭제 — 앱별로 연결 유무를 판정")
    void deleteUnusedClientsDeletesOnlyClientsWithoutAuthorizations() {
        insertRegisteredClient("id-unused", "client-unused", NOW.minus(Duration.ofDays(10)));
        insertRegisteredClient("id-used", "client-used", NOW.minus(Duration.ofDays(10)));
        insertAuthorization(PRINCIPAL, "id-used", NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(2)),
                "refresh-used", NOW.plus(Duration.ofDays(28)));

        int deleted = oAuthGrantRepository.deleteUnusedClients(NOW);

        assertThat(deleted).isEqualTo(1);
        assertThat(remainingClientIds()).containsExactly("id-used");
    }

    @Test
    @DisplayName("DCR 모양(UUID id·랜덤 client_id)과 CIMD 모양(https client_id)을 같은 기준으로 삭제")
    void deleteUnusedClientsDeletesDcrAndCimdShapedClientsAlike() {
        Instant old = NOW.minus(Duration.ofDays(10));
        insertRegisteredClient(UUID.randomUUID().toString(), "mcp-" + UUID.randomUUID(), old);
        insertRegisteredClient(UUID.randomUUID().toString(), "https://client.example.com/oauth/metadata.json", old);
        insertRegisteredClient("id-recent-cimd", "https://recent.example.com/oauth/metadata.json", NOW.plusSeconds(1));

        int deleted = oAuthGrantRepository.deleteUnusedClients(NOW);

        assertThat(deleted).isEqualTo(2);
        assertThat(remainingClientIds()).containsExactly("id-recent-cimd");
    }

    @Test
    @DisplayName("앱 행을 지워도 oauth2_authorization 행은 건드리지 않는다")
    void deleteUnusedClientsLeavesAuthorizationRows() {
        insertRegisteredClient("id-unused", "client-unused", NOW.minus(Duration.ofDays(10)));
        String otherClientAuthorization = insertAuthorization(PRINCIPAL, CLIENT_A, NOW.minusSeconds(60),
                NOW.minusSeconds(55), "refresh-other", NOW.plus(Duration.ofDays(29)));

        oAuthGrantRepository.deleteUnusedClients(NOW);

        assertThat(remainingAuthorizationIds()).containsExactly(otherClientAuthorization);
    }

    private void insertRegisteredClient(String id, String clientId, Instant issuedAt) {
        jdbcTemplate.update("""
                INSERT INTO oauth2_registered_client
                    (id, client_id, client_id_issued_at, client_name, client_authentication_methods,
                     authorization_grant_types, scopes, client_settings, token_settings)
                VALUES (?, ?, ?, 'Test Client', 'none', 'authorization_code,refresh_token', 'mcp:query', '{}', '{}')
                """, id, clientId, ts(issuedAt));
    }

    private List<String> remainingClientIds() {
        return jdbcTemplate.queryForList("SELECT id FROM oauth2_registered_client", String.class);
    }

    private String insertAuthorization(
            String principal,
            String registeredClientId,
            Instant codeIssuedAt,
            Instant accessIssuedAt,
            String refreshTokenValue,
            Instant refreshExpiresAt
    ) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO oauth2_authorization
                    (id, registered_client_id, principal_name, authorization_grant_type,
                     authorization_code_issued_at, access_token_issued_at,
                     refresh_token_value, refresh_token_expires_at)
                VALUES (?, ?, ?, 'authorization_code', ?, ?, ?, ?)
                """, id, registeredClientId, principal, ts(codeIssuedAt), ts(accessIssuedAt),
                refreshTokenValue, ts(refreshExpiresAt));
        return id;
    }

    // 만료 시각 컬럼 세 개만 채운 행 — deleteExpired는 이 컬럼만 본다
    private String insertWithExpiry(
            String principal,
            String registeredClientId,
            Instant codeExpiresAt,
            Instant accessExpiresAt,
            Instant refreshExpiresAt
    ) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO oauth2_authorization
                    (id, registered_client_id, principal_name, authorization_grant_type,
                     authorization_code_expires_at, access_token_expires_at, refresh_token_expires_at)
                VALUES (?, ?, ?, 'authorization_code', ?, ?, ?)
                """, id, registeredClientId, principal,
                ts(codeExpiresAt), ts(accessExpiresAt), ts(refreshExpiresAt));
        return id;
    }

    private List<String> remainingConsentKeys() {
        return jdbcTemplate.queryForList(
                "SELECT registered_client_id || '/' || principal_name FROM oauth2_authorization_consent", String.class);
    }

    private void insertConsent(String registeredClientId, String principal) {
        jdbcTemplate.update("""
                INSERT INTO oauth2_authorization_consent (registered_client_id, principal_name, authorities)
                VALUES (?, ?, 'SCOPE_mcp:query')
                """, registeredClientId, principal);
    }

    private List<String> remainingAuthorizationIds() {
        return jdbcTemplate.queryForList("SELECT id FROM oauth2_authorization", String.class);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
