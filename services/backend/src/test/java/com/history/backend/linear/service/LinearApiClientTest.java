package com.history.backend.linear.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

// Linear는 REST가 아니라 단일 GraphQL 엔드포인트다 — 오류도 HTTP 200으로 내려주므로
// 응답 바디의 errors 배열을 별도로 확인해야 한다(그렇지 않으면 실패를 성공으로 오인한다).
@DisplayName("LinearApiClient: Linear GraphQL API 호출")
class LinearApiClientTest {

    @Test
    @DisplayName("팀 목록 조회 성공 → GraphQL 엔드포인트에 teams 쿼리·Bearer 헤더로 POST, 팀 목록 반환")
    void listTeamsReturnsTeamList() {
        LinearApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.linear.app/graphql"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer linear-access-token"))
                .andExpect(jsonPath("$.query").value(containsString("teams")))
                .andRespond(withSuccess("""
                        {
                          "data": {
                            "teams": {
                              "nodes": [
                                { "id": "team-1", "name": "Engineering" },
                                { "id": "team-2", "name": "Design" }
                              ]
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        List<LinearApiClient.LinearTeam> result = fixture.client.listTeams("linear-access-token");

        assertThat(result).containsExactly(
                new LinearApiClient.LinearTeam("team-1", "Engineering"),
                new LinearApiClient.LinearTeam("team-2", "Design")
        );
        fixture.server.verify();
    }

    @Test
    @DisplayName("HTTP 오류 응답 → UnauthorizedException 발생")
    void listTeamsRejectsHttpErrorResponse() {
        LinearApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.linear.app/graphql"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.listTeams("bad-token"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("HTTP 200이지만 errors 배열이 있으면 BadGatewayException 발생 (Linear는 오류도 200으로 응답)")
    void listTeamsRejectsGraphQlErrorsDespiteHttp200() {
        LinearApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.linear.app/graphql"))
                .andRespond(withSuccess("""
                        {
                          "errors": [
                            { "message": "Authentication required." }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.listTeams("linear-access-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("nodes 누락 응답 → BadGatewayException 발생")
    void listTeamsRejectsMissingNodes() {
        LinearApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.linear.app/graphql"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.listTeams("linear-access-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    private LinearApiClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LinearApiClient client = new LinearApiClient(builder.build());
        return new LinearApiClientFixture(client, server);
    }

    private record LinearApiClientFixture(LinearApiClient client, MockRestServiceServer server) {
    }
}
