package com.history.backend.clickup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;

import java.util.List;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

// ClickUp REST API 클라이언트 (OAuth access token 기반). 계층 조회 4단(team → space → folder → list)
// 이고, folder는 건너뛸 수 있어 space에서 바로(folderless) list를 조회하는 경로도 있다. 응답은
// Asana와 달리 엔드포인트마다 래핑 키가 다르다("teams"/"spaces"/"folders"/"lists").
@DisplayName("ClickUpApiClient: ClickUp REST API 호출")
class ClickUpApiClientTest {

    @Test
    @DisplayName("워크스페이스 목록 조회 성공 → Bearer 헤더로 GET /team, teams 언래핑해 목록 반환")
    void listWorkspacesReturnsWorkspaceList() {
        ClickUpApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/team"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer clickup-access-token"))
                .andRespond(withSuccess("""
                        {
                          "teams": [
                            { "id": "team-1", "name": "Acme" },
                            { "id": "team-2", "name": "Beta Corp" }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<ClickUpApiClient.ClickUpWorkspace> result = fixture.client.listWorkspaces("clickup-access-token");

        assertThat(result).containsExactly(
                new ClickUpApiClient.ClickUpWorkspace("team-1", "Acme"),
                new ClickUpApiClient.ClickUpWorkspace("team-2", "Beta Corp")
        );
        fixture.server.verify();
    }

    @Test
    @DisplayName("워크스페이스 목록 조회 HTTP 오류 응답 → UnauthorizedException 발생")
    void listWorkspacesRejectsHttpErrorResponse() {
        ClickUpApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/team"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.listWorkspaces("bad-token"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("워크스페이스 목록 조회 HTTP 429(rate limit) 응답 → BadGatewayException 발생 (일시 장애, 토큰 무효 오진단 방지)")
    void listWorkspacesRejectsTooManyRequestsAsBadGateway() {
        ClickUpApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/team"))
                .andRespond(withTooManyRequests());

        assertThatThrownBy(() -> fixture.client.listWorkspaces("clickup-access-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("워크스페이스 목록 조회 HTTP 5xx 응답 → BadGatewayException 발생")
    void listWorkspacesRejectsServerErrorAsBadGateway() {
        ClickUpApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/team"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.listWorkspaces("clickup-access-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("스페이스 목록 조회 성공 → Bearer 헤더로 GET /team/{id}/space, spaces 언래핑해 목록 반환")
    void listSpacesReturnsSpaceListForTeam() {
        ClickUpApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/team/team-1/space"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer clickup-access-token"))
                .andRespond(withSuccess("""
                        {
                          "spaces": [
                            { "id": "space-1", "name": "Engineering" },
                            { "id": "space-2", "name": "Marketing" }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<ClickUpApiClient.ClickUpSpace> result = fixture.client.listSpaces("team-1", "clickup-access-token");

        assertThat(result).containsExactly(
                new ClickUpApiClient.ClickUpSpace("space-1", "Engineering"),
                new ClickUpApiClient.ClickUpSpace("space-2", "Marketing")
        );
        fixture.server.verify();
    }

    @Test
    @DisplayName("스페이스 목록 조회 HTTP 오류 응답 → UnauthorizedException 발생")
    void listSpacesRejectsHttpErrorResponse() {
        ClickUpApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/team/team-1/space"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.listSpaces("team-1", "bad-token"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("스페이스 목록 조회 HTTP 429(rate limit) 응답 → BadGatewayException 발생 (일시 장애, 토큰 무효 오진단 방지)")
    void listSpacesRejectsTooManyRequestsAsBadGateway() {
        ClickUpApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/team/team-1/space"))
                .andRespond(withTooManyRequests());

        assertThatThrownBy(() -> fixture.client.listSpaces("team-1", "clickup-access-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("폴더 목록 조회 성공 → Bearer 헤더로 GET /space/{id}/folder, folders 언래핑해 목록 반환")
    void listFoldersReturnsFolderListForSpace() {
        ClickUpApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/space/space-1/folder"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer clickup-access-token"))
                .andRespond(withSuccess("""
                        {
                          "folders": [
                            { "id": "folder-1", "name": "Sprint Backlog" },
                            { "id": "folder-2", "name": "Archived" }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<ClickUpApiClient.ClickUpFolder> result = fixture.client.listFolders("space-1", "clickup-access-token");

        assertThat(result).containsExactly(
                new ClickUpApiClient.ClickUpFolder("folder-1", "Sprint Backlog"),
                new ClickUpApiClient.ClickUpFolder("folder-2", "Archived")
        );
        fixture.server.verify();
    }

    @Test
    @DisplayName("폴더 목록 조회 HTTP 오류 응답 → UnauthorizedException 발생")
    void listFoldersRejectsHttpErrorResponse() {
        ClickUpApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/space/space-1/folder"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.listFolders("space-1", "bad-token"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("폴더 목록 조회 HTTP 429(rate limit) 응답 → BadGatewayException 발생 (일시 장애, 토큰 무효 오진단 방지)")
    void listFoldersRejectsTooManyRequestsAsBadGateway() {
        ClickUpApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/space/space-1/folder"))
                .andRespond(withTooManyRequests());

        assertThatThrownBy(() -> fixture.client.listFolders("space-1", "clickup-access-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("폴더 안 리스트 목록 조회 성공 → Bearer 헤더로 GET /folder/{id}/list, lists 언래핑해 목록 반환")
    void listFolderListsReturnsListsForFolder() {
        ClickUpApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/folder/folder-1/list"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer clickup-access-token"))
                .andRespond(withSuccess("""
                        {
                          "lists": [
                            { "id": "list-1", "name": "To Do" },
                            { "id": "list-2", "name": "Done" }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<ClickUpApiClient.ClickUpList> result = fixture.client.listFolderLists("folder-1", "clickup-access-token");

        assertThat(result).containsExactly(
                new ClickUpApiClient.ClickUpList("list-1", "To Do"),
                new ClickUpApiClient.ClickUpList("list-2", "Done")
        );
        fixture.server.verify();
    }

    @Test
    @DisplayName("폴더 안 리스트 목록 조회 HTTP 오류 응답 → UnauthorizedException 발생")
    void listFolderListsRejectsHttpErrorResponse() {
        ClickUpApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/folder/folder-1/list"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.listFolderLists("folder-1", "bad-token"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("폴더 안 리스트 목록 조회 HTTP 429(rate limit) 응답 → BadGatewayException 발생 (일시 장애, 토큰 무효 오진단 방지)")
    void listFolderListsRejectsTooManyRequestsAsBadGateway() {
        ClickUpApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/folder/folder-1/list"))
                .andRespond(withTooManyRequests());

        assertThatThrownBy(() -> fixture.client.listFolderLists("folder-1", "clickup-access-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("폴더 없는(folderless) 리스트 목록 조회 성공 → Bearer 헤더로 GET /space/{id}/list, lists 언래핑해 목록 반환")
    void listFolderlessListsReturnsListsForSpace() {
        ClickUpApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/space/space-1/list"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer clickup-access-token"))
                .andRespond(withSuccess("""
                        {
                          "lists": [
                            { "id": "list-3", "name": "Backlog" }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<ClickUpApiClient.ClickUpList> result = fixture.client.listFolderlessLists("space-1", "clickup-access-token");

        assertThat(result).containsExactly(new ClickUpApiClient.ClickUpList("list-3", "Backlog"));
        fixture.server.verify();
    }

    @Test
    @DisplayName("폴더 없는(folderless) 리스트 목록 조회 HTTP 오류 응답 → UnauthorizedException 발생")
    void listFolderlessListsRejectsHttpErrorResponse() {
        ClickUpApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/space/space-1/list"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.listFolderlessLists("space-1", "bad-token"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("폴더 없는(folderless) 리스트 목록 조회 HTTP 429(rate limit) 응답 → BadGatewayException 발생 (일시 장애, 토큰 무효 오진단 방지)")
    void listFolderlessListsRejectsTooManyRequestsAsBadGateway() {
        ClickUpApiClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.clickup.com/api/v2/space/space-1/list"))
                .andRespond(withTooManyRequests());

        assertThatThrownBy(() -> fixture.client.listFolderlessLists("space-1", "clickup-access-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    private ClickUpApiClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ClickUpApiClient client = new ClickUpApiClient(builder.build());
        return new ClickUpApiClientFixture(client, server);
    }

    private record ClickUpApiClientFixture(ClickUpApiClient client, MockRestServiceServer server) {
    }
}
