package com.history.backend.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.integration.service.OAuthStateService;
import com.history.backend.security.JwtProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("ConsentTicketService: 동의 티켓 발급·1회 소비")
class ConsentTicketServiceTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");
    private static final Instant NOW = Instant.parse("2026-09-30T03:00:00Z");
    private static final String RAW_QUERY = "response_type=code&client_id=abc&redirect_uri=http%3A%2F%2Flocalhost%3A53421%2Fcallback&state=s1";
    private static final JwtProperties JWT_PROPERTIES =
            new JwtProperties("test-secret", Duration.ofMinutes(15), Duration.ofDays(14));
    private static final JwtProperties OTHER_JWT_PROPERTIES =
            new JwtProperties("other-secret", Duration.ofMinutes(15), Duration.ofDays(14));
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final Pattern REJECT_REASON = Pattern.compile("Consent ticket rejected\\. reason=(\\w+)");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("TTL은 60초")
    void ttlIsSixtySeconds() {
        assertThat(ConsentTicketService.TTL).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("발급한 티켓을 같은 쿼리로 소비하면 userId 반환")
    void issueAndConsumeRoundTripsUserId() {
        ConsentTicketService service = service(JWT_PROPERTIES, NOW);

        String ticket = service.issue(USER_ID, RAW_QUERY);

        assertThat(service.consume(ticket, RAW_QUERY)).contains(USER_ID);
    }

    @Test
    @DisplayName("같은 티켓의 두 번째 소비는 empty(nonce 재사용 차단)")
    void rejectsReusedTicket() {
        ConsentTicketService service = service(JWT_PROPERTIES, NOW);
        String ticket = service.issue(USER_ID, RAW_QUERY);

        assertThat(service.consume(ticket, RAW_QUERY)).contains(USER_ID);
        assertThat(service.consume(ticket, RAW_QUERY)).isEmpty();
    }

    @Test
    @DisplayName("같은 입력으로 발급한 두 티켓은 서로 다른 값이라 각각 한 번씩 소비된다")
    void ticketsIssuedForSameInputAreIndependent() {
        ConsentTicketService service = service(JWT_PROPERTIES, NOW);
        String first = service.issue(USER_ID, RAW_QUERY);
        String second = service.issue(USER_ID, RAW_QUERY);

        assertThat(first).isNotEqualTo(second);
        assertThat(service.consume(first, RAW_QUERY)).contains(USER_ID);
        assertThat(service.consume(second, RAW_QUERY)).contains(USER_ID);
    }

    @Test
    @DisplayName("다른 쿼리로 소비하면 empty")
    void rejectsDifferentQuery() {
        ConsentTicketService service = service(JWT_PROPERTIES, NOW);
        String ticket = service.issue(USER_ID, RAW_QUERY);

        assertThat(service.consume(ticket, "response_type=code&client_id=evil")).isEmpty();
    }

    @Test
    @DisplayName("한 글자만 다른 쿼리(state=s1 → s2)도 empty")
    void rejectsQueryDifferingByOneCharacter() {
        ConsentTicketService service = service(JWT_PROPERTIES, NOW);
        String ticket = service.issue(USER_ID, RAW_QUERY);

        assertThat(service.consume(ticket, RAW_QUERY.replace("state=s1", "state=s2"))).isEmpty();
    }

    @Test
    @DisplayName("쿼리가 null이면 빈 문자열과 같게 취급한다")
    void treatsNullQueryAsEmptyString() {
        ConsentTicketService service = service(JWT_PROPERTIES, NOW);

        assertThat(service.consume(service.issue(USER_ID, null), "")).contains(USER_ID);
        assertThat(service.consume(service.issue(USER_ID, ""), null)).contains(USER_ID);
    }

    @Test
    @DisplayName("null로 발급한 티켓을 비어 있지 않은 쿼리로 소비하면 empty")
    void rejectsNonEmptyQueryForTicketIssuedWithNullQuery() {
        ConsentTicketService service = service(JWT_PROPERTIES, NOW);
        String ticket = service.issue(USER_ID, null);

        assertThat(service.consume(ticket, RAW_QUERY)).isEmpty();
    }

    @Test
    @DisplayName("60초가 지난 티켓은 empty")
    void rejectsTicketAfterSixtySeconds() {
        String ticket = service(JWT_PROPERTIES, NOW).issue(USER_ID, RAW_QUERY);
        ConsentTicketService verifier = service(JWT_PROPERTIES, NOW.plusSeconds(60));

        assertThat(verifier.consume(ticket, RAW_QUERY)).isEmpty();
    }

    @Test
    @DisplayName("59초 뒤까지는 소비 성공")
    void acceptsTicketBeforeSixtySeconds() {
        String ticket = service(JWT_PROPERTIES, NOW).issue(USER_ID, RAW_QUERY);
        ConsentTicketService verifier = service(JWT_PROPERTIES, NOW.plusSeconds(59));

        assertThat(verifier.consume(ticket, RAW_QUERY)).contains(USER_ID);
    }

    @Test
    @DisplayName("서명이 위조된 티켓은 empty")
    void rejectsTamperedSignature() {
        ConsentTicketService service = service(JWT_PROPERTIES, NOW);
        String[] parts = service.issue(USER_ID, RAW_QUERY).split("\\.");
        String tamperedSignature = (parts[1].charAt(0) == 'a' ? "b" : "a") + parts[1].substring(1);

        assertThat(service.consume(parts[0] + "." + tamperedSignature, RAW_QUERY)).isEmpty();
    }

    @Test
    @DisplayName("페이로드(uid)를 바꿔치기한 티켓은 서명 불일치로 empty")
    void rejectsTamperedPayload() throws Exception {
        ConsentTicketService service = service(JWT_PROPERTIES, NOW);
        String[] parts = service.issue(USER_ID, RAW_QUERY).split("\\.");
        Map<String, Object> claims = readClaims(parts[0]);
        claims.put("uid", UUID.randomUUID().toString());

        assertThat(service.consume(encode(claims) + "." + parts[1], RAW_QUERY)).isEmpty();
    }

    @Test
    @DisplayName("다른 secret으로 서명된 티켓은 empty")
    void rejectsTicketSignedWithOtherSecret() {
        String ticket = service(OTHER_JWT_PROPERTIES, NOW).issue(USER_ID, RAW_QUERY);

        assertThat(service(JWT_PROPERTIES, NOW).consume(ticket, RAW_QUERY)).isEmpty();
    }

    @Test
    @DisplayName("같은 secret으로 서명된 OAuth 콜백 state를 티켓으로 넣으면 empty")
    void rejectsOAuthStateSignedWithSameSecret() {
        OAuthStateService stateService = new OAuthStateService(JWT_PROPERTIES);
        String state = stateService.issue(PROJECT_ID, USER_ID, "slack");

        assertThat(service(JWT_PROPERTIES, NOW).consume(state, RAW_QUERY)).isEmpty();
    }

    @Test
    @DisplayName("서명은 유효하지만 typ만 다른 티켓은 empty")
    void rejectsValidlySignedTicketWithDifferentType() throws Exception {
        ConsentTicketService service = service(JWT_PROPERTIES, NOW);
        String payload = service.issue(USER_ID, RAW_QUERY).split("\\.")[0];
        Map<String, Object> claims = readClaims(payload);
        claims.put("typ", "oauth_state");
        String forgedPayload = encode(claims);

        assertThat(service.consume(forgedPayload + "." + sign(JWT_PROPERTIES, forgedPayload), RAW_QUERY)).isEmpty();
    }

    @Test
    @DisplayName("null·빈 문자열·점이 없거나 조각이 많은 티켓은 예외 없이 empty")
    void rejectsMalformedTickets() {
        ConsentTicketService service = service(JWT_PROPERTIES, NOW);

        assertThat(service.consume(null, RAW_QUERY)).isEmpty();
        assertThat(service.consume("", RAW_QUERY)).isEmpty();
        assertThat(service.consume("no-dot-ticket", RAW_QUERY)).isEmpty();
        assertThat(service.consume("a.b.c", RAW_QUERY)).isEmpty();
        assertThat(service.consume("!!!.???", RAW_QUERY)).isEmpty();
    }

    @Test
    @DisplayName("실패한 소비는 nonce를 소모하지 않아 이후 맞는 쿼리로는 성공")
    void failedConsumeDoesNotBurnNonce() {
        ConsentTicketService service = service(JWT_PROPERTIES, NOW);
        String ticket = service.issue(USER_ID, RAW_QUERY);

        assertThat(service.consume(ticket, "state=wrong")).isEmpty();
        assertThat(service.consume(ticket, RAW_QUERY)).contains(USER_ID);
    }

    @Test
    @DisplayName("형식이 깨진 티켓(null·빈 문자열·조각 수 오류·해석 실패·필수 클레임 누락)은 reason=malformed만 남긴다")
    void logsMalformedReasonForBrokenTickets(CapturedOutput output) throws Exception {
        ConsentTicketService service = service(JWT_PROPERTIES, NOW);
        String payload = readClaimsEncodedWithoutExp(service);

        service.consume(null, RAW_QUERY);
        service.consume("", RAW_QUERY);
        service.consume("no-dot-ticket", RAW_QUERY);
        service.consume("!!!.???", RAW_QUERY);
        service.consume(payload + "." + sign(JWT_PROPERTIES, payload), RAW_QUERY);

        assertThat(rejectReasons(output)).containsExactly(
                "malformed", "malformed", "malformed", "malformed", "malformed");
    }

    @Test
    @DisplayName("위조된 서명·페이로드 바꿔치기·다른 secret은 reason=signature만 남긴다")
    void logsSignatureReasonForBadSignature(CapturedOutput output) throws Exception {
        ConsentTicketService service = service(JWT_PROPERTIES, NOW);
        String[] parts = service.issue(USER_ID, RAW_QUERY).split("\\.");
        String tamperedSignature = (parts[1].charAt(0) == 'a' ? "b" : "a") + parts[1].substring(1);
        Map<String, Object> claims = readClaims(parts[0]);
        claims.put("uid", UUID.randomUUID().toString());

        service.consume(parts[0] + "." + tamperedSignature, RAW_QUERY);
        service.consume(encode(claims) + "." + parts[1], RAW_QUERY);
        service.consume(service(OTHER_JWT_PROPERTIES, NOW).issue(USER_ID, RAW_QUERY), RAW_QUERY);

        assertThat(rejectReasons(output)).containsExactly("signature", "signature", "signature");
    }

    @Test
    @DisplayName("서명은 맞지만 typ이 다르면 reason=type만 남긴다")
    void logsTypeReasonForWrongType(CapturedOutput output) throws Exception {
        ConsentTicketService service = service(JWT_PROPERTIES, NOW);
        Map<String, Object> claims = readClaims(service.issue(USER_ID, RAW_QUERY).split("\\.")[0]);
        claims.put("typ", "oauth_state");
        String forgedPayload = encode(claims);

        service.consume(forgedPayload + "." + sign(JWT_PROPERTIES, forgedPayload), RAW_QUERY);

        assertThat(rejectReasons(output)).containsExactly("type");
    }

    @Test
    @DisplayName("만료된 티켓은 reason=expired만 남긴다")
    void logsExpiredReasonForExpiredTicket(CapturedOutput output) {
        String ticket = service(JWT_PROPERTIES, NOW).issue(USER_ID, RAW_QUERY);

        service(JWT_PROPERTIES, NOW.plusSeconds(60)).consume(ticket, RAW_QUERY);

        assertThat(rejectReasons(output)).containsExactly("expired");
    }

    @Test
    @DisplayName("쿼리가 다르면 reason=query_mismatch만 남기고 티켓·쿼리·사용자 id는 로그에 없다")
    void logsQueryMismatchReasonWithoutSensitiveValues(CapturedOutput output) {
        ConsentTicketService service = service(JWT_PROPERTIES, NOW);
        String ticket = service.issue(USER_ID, RAW_QUERY);
        String otherQuery = "response_type=code&client_id=evil";

        service.consume(ticket, otherQuery);

        assertThat(rejectReasons(output)).containsExactly("query_mismatch");
        assertNoSensitiveValues(output, ticket, RAW_QUERY, otherQuery);
    }

    @Test
    @DisplayName("이미 소비된 티켓은 reason=reused만 남기고 티켓·쿼리·사용자 id는 로그에 없다")
    void logsReusedReasonWithoutSensitiveValues(CapturedOutput output) {
        ConsentTicketService service = service(JWT_PROPERTIES, NOW);
        String ticket = service.issue(USER_ID, RAW_QUERY);

        service.consume(ticket, RAW_QUERY);
        service.consume(ticket, RAW_QUERY);

        assertThat(rejectReasons(output)).containsExactly("reused");
        assertNoSensitiveValues(output, ticket, RAW_QUERY);
    }

    @Test
    @DisplayName("소비에 성공하면 거부 로그를 남기지 않는다")
    void doesNotLogRejectionOnSuccessfulConsume(CapturedOutput output) {
        ConsentTicketService service = service(JWT_PROPERTIES, NOW);

        assertThat(service.consume(service.issue(USER_ID, RAW_QUERY), RAW_QUERY)).contains(USER_ID);

        assertThat(output.getAll()).doesNotContain("Consent ticket rejected");
    }

    private List<String> rejectReasons(CapturedOutput output) {
        List<String> reasons = new ArrayList<>();
        Matcher matcher = REJECT_REASON.matcher(output.getAll());
        while (matcher.find()) {
            reasons.add(matcher.group(1));
        }
        return reasons;
    }

    private void assertNoSensitiveValues(CapturedOutput output, String ticket, String... queries) {
        String[] parts = ticket.split("\\.");
        assertThat(output.getAll())
                .doesNotContain(ticket)
                .doesNotContain(parts[0])
                .doesNotContain(parts[1])
                .doesNotContain(USER_ID.toString());
        for (String query : queries) {
            assertThat(output.getAll()).doesNotContain(query);
        }
    }

    // exp 클레임이 빠진, 서명은 유효한 페이로드
    private String readClaimsEncodedWithoutExp(ConsentTicketService service) throws Exception {
        Map<String, Object> claims = readClaims(service.issue(USER_ID, RAW_QUERY).split("\\.")[0]);
        claims.remove("exp");
        return encode(claims);
    }

    private ConsentTicketService service(JwtProperties jwtProperties, Instant now) {
        return new ConsentTicketService(jwtProperties, Clock.fixed(now, ZoneOffset.UTC));
    }

    private Map<String, Object> readClaims(String payload) throws Exception {
        return objectMapper.readValue(BASE64_URL_DECODER.decode(payload), new TypeReference<>() {
        });
    }

    private String encode(Map<String, Object> claims) throws Exception {
        return BASE64_URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(claims));
    }

    private String sign(JwtProperties jwtProperties, String signingInput) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(jwtProperties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return BASE64_URL_ENCODER.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
    }
}
