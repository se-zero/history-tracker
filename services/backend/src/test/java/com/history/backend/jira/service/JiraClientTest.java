package com.history.backend.jira.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.jira.AtlassianProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("JiraClient: Jira REST API 호출")
class JiraClientTest {

    @Test
    @DisplayName("프로젝트 목록 조회 성공 → cloudId 게이트웨이 URI·Bearer 헤더로 요청, 프로젝트 목록 반환")
    void listProjectsReturnsProjectList() {
        JiraClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://atlassian.test/ex/jira/cloud-1/rest/api/3/project/search?maxResults=100"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer atl-access-token"))
                .andExpect(header("Accept", "application/json"))
                .andRespond(withSuccess("""
                        {
                          "values": [
                            { "key": "PROJ", "name": "Project" },
                            { "key": "PLAT", "name": "Platform" }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<JiraClient.JiraProject> result = fixture.client.listProjects("cloud-1", "atl-access-token");

        assertThat(result).containsExactly(
                new JiraClient.JiraProject("PROJ", "Project"),
                new JiraClient.JiraProject("PLAT", "Platform")
        );
        fixture.server.verify();
    }

    @Test
    @DisplayName("HTTP 오류 응답 → UnauthorizedException 발생")
    void listProjectsRejectsHttpErrorResponse() {
        JiraClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://atlassian.test/ex/jira/cloud-1/rest/api/3/project/search?maxResults=100"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.listProjects("cloud-1", "bad-token"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("values 누락 응답 → BadGatewayException 발생")
    void listProjectsRejectsMissingValues() {
        JiraClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://atlassian.test/ex/jira/cloud-1/rest/api/3/project/search?maxResults=100"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.listProjects("cloud-1", "atl-access-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    private JiraClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        JiraClient client = new JiraClient(
                new AtlassianProperties(
                        "test-client-id",
                        "test-client-secret",
                        "https://atlassian.test/callback",
                        "read:jira-work read:jira-user offline_access",
                        "https://atlassian.test/authorize",
                        "https://atlassian.test/oauth/token",
                        "https://atlassian.test/oauth/token/accessible-resources",
                        "https://atlassian.test/oauth/revoke",
                        "https://atlassian.test/ex/jira",
                        Duration.ofMinutes(5)
                ),
                builder.build()
        );
        return new JiraClientFixture(client, server);
    }

    private record JiraClientFixture(JiraClient client, MockRestServiceServer server) {
    }
}
