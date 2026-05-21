package com.history.backend.jira.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class JiraClientTest {

    @Test
    void verifyProjectReturnsProjectInformation() {
        JiraClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://example.atlassian.net/rest/api/3/project/PROJ"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(
                        "Authorization",
                        "Basic b3duZXJAZXhhbXBsZS5jb206amlyYS10b2tlbg=="
                ))
                .andExpect(header("Accept", "application/json"))
                .andRespond(withSuccess("""
                        {
                          "key": "PROJ",
                          "name": "Project"
                        }
                        """, MediaType.APPLICATION_JSON));

        JiraClient.JiraProject result = fixture.client.verifyProject(
                "https://example.atlassian.net",
                "PROJ",
                "owner@example.com",
                "jira-token"
        );

        assertThat(result.key()).isEqualTo("PROJ");
        assertThat(result.name()).isEqualTo("Project");
        fixture.server.verify();
    }

    @Test
    void verifyProjectRejectsHttpErrorResponse() {
        JiraClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://example.atlassian.net/rest/api/3/project/PROJ"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.verifyProject(
                "https://example.atlassian.net",
                "PROJ",
                "owner@example.com",
                "bad-token"
        ))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid Jira credentials or project.");
        fixture.server.verify();
    }

    @Test
    void verifyProjectRejectsMissingProjectInformation() {
        JiraClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://example.atlassian.net/rest/api/3/project/PROJ"))
                .andRespond(withSuccess("""
                        {
                          "key": "",
                          "name": "Project"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.verifyProject(
                "https://example.atlassian.net",
                "PROJ",
                "owner@example.com",
                "jira-token"
        ))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Jira project verification response is missing project information.");
        fixture.server.verify();
    }

    private JiraClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        JiraClient client = new JiraClient(builder.build());
        return new JiraClientFixture(client, server);
    }

    private record JiraClientFixture(JiraClient client, MockRestServiceServer server) {
    }
}
