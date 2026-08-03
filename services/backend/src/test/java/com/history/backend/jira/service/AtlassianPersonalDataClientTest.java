package com.history.backend.jira.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.jira.AtlassianPersonalDataReportProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("AtlassianPersonalDataClient: Jira 개인정보 보고 API 호출")
class AtlassianPersonalDataClientTest {

    @Test
    @DisplayName("보고 요청 성공 → reportUrl에 Bearer 헤더·accounts 본문(updatedAt은 ISO_INSTANT)으로 POST, 204는 빈 outcome")
    void reportSendsAccountsWithBearerHeaderAndJsonBody() {
        AtlassianPersonalDataClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/report-accounts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer atl-bot-access-token"))
                .andExpect(content().json("""
                        {"accounts": [{"accountId": "712020:abc", "updatedAt": "2026-08-01T00:00:00Z"}]}
                        """, JsonCompareMode.STRICT))
                .andRespond(withNoContent());

        AtlassianPersonalDataClient.ReportOutcome result = fixture.client.report(
                List.of(new AtlassianPersonalDataClient.ReportAccount(
                        "712020:abc", Instant.parse("2026-08-01T00:00:00Z"))),
                "atl-bot-access-token");

        assertThat(result.closedAccountIds()).isEmpty();
        assertThat(result.updatedAccountIds()).isEmpty();
        fixture.server.verify();
    }

    @Test
    @DisplayName("200 응답 → status별로 closed/updated 분류, 미지의 status는 무시")
    void reportClassifiesAccountsByStatus() {
        AtlassianPersonalDataClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/report-accounts"))
                .andRespond(withSuccess("""
                        {"accounts": [
                          {"accountId": "a1", "status": "closed"},
                          {"accountId": "a2", "status": "updated"},
                          {"accountId": "a3", "status": "something-else"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        AtlassianPersonalDataClient.ReportOutcome result = fixture.client.report(
                List.of(
                        new AtlassianPersonalDataClient.ReportAccount("a1", Instant.parse("2026-08-01T00:00:00Z")),
                        new AtlassianPersonalDataClient.ReportAccount("a2", Instant.parse("2026-08-01T00:00:00Z")),
                        new AtlassianPersonalDataClient.ReportAccount("a3", Instant.parse("2026-08-01T00:00:00Z"))),
                "atl-bot-access-token");

        assertThat(result.closedAccountIds()).containsExactly("a1");
        assertThat(result.updatedAccountIds()).containsExactly("a2");
        fixture.server.verify();
    }

    @Test
    @DisplayName("HTTP 429(rate limit) 응답 → RateLimitedException 발생 (이번 회차 건너뛰기 용)")
    void reportRejectsTooManyRequestsAsRateLimited() {
        AtlassianPersonalDataClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/report-accounts"))
                .andRespond(withTooManyRequests());

        assertThatThrownBy(() -> fixture.client.report(
                List.of(new AtlassianPersonalDataClient.ReportAccount(
                        "712020:abc", Instant.parse("2026-08-01T00:00:00Z"))),
                "atl-bot-access-token"))
                .isInstanceOf(AtlassianPersonalDataClient.RateLimitedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("HTTP 401 응답 → UnauthorizedException 발생")
    void reportRejectsUnauthorizedResponse() {
        AtlassianPersonalDataClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/report-accounts"))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> fixture.client.report(
                List.of(new AtlassianPersonalDataClient.ReportAccount(
                        "712020:abc", Instant.parse("2026-08-01T00:00:00Z"))),
                "atl-bot-access-token"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("HTTP 5xx 응답 → BadGatewayException 발생")
    void reportRejectsServerErrorAsBadGateway() {
        AtlassianPersonalDataClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/report-accounts"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.report(
                List.of(new AtlassianPersonalDataClient.ReportAccount(
                        "712020:abc", Instant.parse("2026-08-01T00:00:00Z"))),
                "atl-bot-access-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    private AtlassianPersonalDataClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtlassianPersonalDataClient client = new AtlassianPersonalDataClient(
                new AtlassianPersonalDataReportProperties(
                        true,
                        "0 0 3 * * *",
                        Duration.ofDays(7),
                        "https://atlassian.test/report-accounts"
                ),
                builder.build()
        );
        return new AtlassianPersonalDataClientFixture(client, server);
    }

    private record AtlassianPersonalDataClientFixture(
            AtlassianPersonalDataClient client, MockRestServiceServer server) {
    }
}
