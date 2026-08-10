package com.history.backend.asana.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
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

// Asana REST API 클라이언트 (OAuth access token 기반). 응답은 {"data": [...]}로 감싸져 있어
// 언래핑이 필요하다.
@DisplayName("AsanaApiClient: Asana REST API 호출")
class AsanaApiClientTest {

    @Test
    @DisplayName("워크스페이스 목록 조회 성공 → limit=100·Bearer 헤더로 GET, data 언래핑해 목록 반환")
    void listWorkspacesReturnsWorkspaceList() {
        AsanaApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://app.asana.com/api/1.0/workspaces?limit=100"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer asana-access-token"))
                .andRespond(withSuccess("""
                        {
                          "data": [
                            { "gid": "ws-1", "name": "Acme" },
                            { "gid": "ws-2", "name": "Beta Corp" }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<AsanaApiClient.AsanaWorkspace> result = fixture.client.listWorkspaces("asana-access-token");

        assertThat(result).containsExactly(
                new AsanaApiClient.AsanaWorkspace("ws-1", "Acme"),
                new AsanaApiClient.AsanaWorkspace("ws-2", "Beta Corp")
        );
        fixture.server.verify();
    }

    @Test
    @DisplayName("워크스페이스 목록 조회 HTTP 오류 응답 → UnauthorizedException 발생")
    void listWorkspacesRejectsHttpErrorResponse() {
        AsanaApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://app.asana.com/api/1.0/workspaces?limit=100"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.listWorkspaces("bad-token"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("워크스페이스 목록 응답에 data 누락 → BadGatewayException 발생")
    void listWorkspacesRejectsMissingData() {
        AsanaApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://app.asana.com/api/1.0/workspaces?limit=100"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.listWorkspaces("asana-access-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("프로젝트 목록 조회 성공 → workspace·archived=false·limit=100·Bearer 헤더로 GET, data 언래핑해 목록 반환")
    void listProjectsReturnsProjectListForWorkspace() {
        AsanaApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://app.asana.com/api/1.0/projects?workspace=ws-1&archived=false&limit=100"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer asana-access-token"))
                .andRespond(withSuccess("""
                        {
                          "data": [
                            { "gid": "proj-1", "name": "Roadmap" },
                            { "gid": "proj-2", "name": "Backlog" }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<AsanaApiClient.AsanaProject> result = fixture.client.listProjects("ws-1", "asana-access-token");

        assertThat(result).containsExactly(
                new AsanaApiClient.AsanaProject("proj-1", "Roadmap"),
                new AsanaApiClient.AsanaProject("proj-2", "Backlog")
        );
        fixture.server.verify();
    }

    @Test
    @DisplayName("프로젝트 목록 조회 HTTP 오류 응답 → UnauthorizedException 발생")
    void listProjectsRejectsHttpErrorResponse() {
        AsanaApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://app.asana.com/api/1.0/projects?workspace=ws-1&archived=false&limit=100"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.listProjects("ws-1", "bad-token"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    private AsanaApiClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AsanaApiClient client = new AsanaApiClient(builder.build());
        return new AsanaApiClientFixture(client, server);
    }

    private record AsanaApiClientFixture(AsanaApiClient client, MockRestServiceServer server) {
    }
}
