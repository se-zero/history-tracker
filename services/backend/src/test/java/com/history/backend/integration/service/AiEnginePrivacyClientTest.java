package com.history.backend.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.List;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.integration.dto.EraseAccount;
import com.history.backend.integration.dto.PrivacyApplyResult;
import com.history.backend.integration.dto.PrivacyDueAccount;
import com.history.backend.integration.dto.RefreshAccount;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("AiEnginePrivacyClient: ai-engine 개인정보 보고 HTTP 클라이언트")
class AiEnginePrivacyClientTest {

    @Test
    @DisplayName("보고 대상 조회 — due_before(ISO_INSTANT)·limit 쿼리 파라미터로 GET, snake_case 응답을 accountId/updatedAt으로 매핑")
    void fetchesDueAccountsWithQueryParamsAndParsesSnakeCaseResponse() {
        AiEnginePrivacyClientFixture fixture = fixture();
        fixture.server.expect(once(),
                        requestTo(Matchers.startsWith("https://ai-engine.test/privacy/jira/accounts")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("due_before", "2026-03-01T12%3A00%3A00Z"))
                .andExpect(queryParam("limit", "50"))
                .andRespond(withSuccess("""
                        [{"account_id": "712020:abc", "updated_at": "2026-03-01T12:00:00+00:00"}]
                        """, MediaType.APPLICATION_JSON));

        List<PrivacyDueAccount> result = fixture.client.fetchDueAccounts(
                Instant.parse("2026-03-01T12:00:00Z"), 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).accountId()).isEqualTo("712020:abc");
        assertThat(result.get(0).updatedAt()).isEqualTo(Instant.parse("2026-03-01T12:00:00Z"));
        fixture.server.verify();
    }

    @Test
    @DisplayName("보고 대상 조회 — 빈 배열 응답이면 빈 리스트(null 아님)")
    void fetchesDueAccountsReturnsEmptyListWhenNoAccountsDue() {
        AiEnginePrivacyClientFixture fixture = fixture();
        fixture.server.expect(once(),
                        requestTo(Matchers.startsWith("https://ai-engine.test/privacy/jira/accounts")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<PrivacyDueAccount> result = fixture.client.fetchDueAccounts(
                Instant.parse("2026-03-01T12:00:00Z"), 50);

        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
        fixture.server.verify();
    }

    @Test
    @DisplayName("보고 대상 조회 — 5xx 응답 시 BadGatewayException 발생")
    void fetchDueAccountsThrowsBadGatewayOnServerError() {
        AiEnginePrivacyClientFixture fixture = fixture();
        fixture.server.expect(once(),
                        requestTo(Matchers.startsWith("https://ai-engine.test/privacy/jira/accounts")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.fetchDueAccounts(Instant.parse("2026-03-01T12:00:00Z"), 50))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("보고 결과 반영 — reported_at(ISO_INSTANT)/ok/erase/refresh(email null 포함) 본문으로 POST, 응답을 erased/refreshed/stamped로 매핑")
    void appliesReportWithSnakeCaseBodyAndParsesResult() {
        AiEnginePrivacyClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/privacy/jira/accounts/apply"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"reported_at": "2026-08-02T00:00:00Z",
                         "ok": ["712020:ok1"],
                         "erase": [{"account_id": "712020:closed", "reason": "closed"}],
                         "refresh": [{"account_id": "712020:refresh", "name": "Younghee Kim", "email": null}]}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"erased": 1, "refreshed": 1, "stamped": 2}
                        """, MediaType.APPLICATION_JSON));

        PrivacyApplyResult result = fixture.client.applyReport(
                Instant.parse("2026-08-02T00:00:00Z"),
                List.of("712020:ok1"),
                List.of(new EraseAccount("712020:closed", "closed")),
                List.of(new RefreshAccount("712020:refresh", "Younghee Kim", null)));

        assertThat(result.erased()).isEqualTo(1);
        assertThat(result.refreshed()).isEqualTo(1);
        assertThat(result.stamped()).isEqualTo(2);
        fixture.server.verify();
    }

    @Test
    @DisplayName("보고 결과 반영 — 5xx 응답 시 BadGatewayException 발생")
    void applyReportThrowsBadGatewayOnServerError() {
        AiEnginePrivacyClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/privacy/jira/accounts/apply"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.applyReport(
                Instant.parse("2026-08-02T00:00:00Z"), List.of(), List.of(), List.of()))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("보고 결과 반영 — body 없는 200 응답 시 BadGatewayException 발생")
    void applyReportThrowsBadGatewayOnEmptyBody() {
        AiEnginePrivacyClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/privacy/jira/accounts/apply"))
                .andRespond(withSuccess());

        assertThatThrownBy(() -> fixture.client.applyReport(
                Instant.parse("2026-08-02T00:00:00Z"), List.of(), List.of(), List.of()))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("액터 이름 검증 — POST 후 mismatches 배열 크기 반환")
    void verifyActorNamesReturnsMismatchCount() {
        AiEnginePrivacyClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/migrations/verify-actor-names"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"checked": 10, "mismatches": [
                          {"uuid": "a1", "project_id": "p1", "name": "Younghee Kim", "expected": "김영희"},
                          {"uuid": "a2", "project_id": "p1", "name": "Foo Bar", "expected": "Foo B."}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        int result = fixture.client.verifyActorNames();

        assertThat(result).isEqualTo(2);
        fixture.server.verify();
    }

    @Test
    @DisplayName("액터 이름 검증 — 5xx 응답 시 BadGatewayException 발생")
    void verifyActorNamesThrowsBadGatewayOnServerError() {
        AiEnginePrivacyClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://ai-engine.test/migrations/verify-actor-names"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.verifyActorNames())
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    private AiEnginePrivacyClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://ai-engine.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiEnginePrivacyClient client = new AiEnginePrivacyClient(builder.build());
        return new AiEnginePrivacyClientFixture(client, server);
    }

    private record AiEnginePrivacyClientFixture(AiEnginePrivacyClient client, MockRestServiceServer server) {
    }
}
