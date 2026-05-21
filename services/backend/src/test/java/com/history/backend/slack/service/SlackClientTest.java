package com.history.backend.slack.service;

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
import com.history.backend.slack.SlackProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SlackClientTest {

    @Test
    void verifyTokenReturnsWorkspaceFromAuthTest() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/auth.test"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer xoxb-token"))
                .andRespond(withSuccess("""
                        {
                          "ok": true,
                          "team": "Acme",
                          "team_id": "T123"
                        }
                        """, MediaType.APPLICATION_JSON));

        SlackClient.SlackWorkspace result = fixture.client.verifyToken("xoxb-token");

        assertThat(result.id()).isEqualTo("T123");
        assertThat(result.name()).isEqualTo("Acme");
        fixture.server.verify();
    }

    @Test
    void verifyTokenRejectsSlackErrorResponse() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/auth.test"))
                .andRespond(withSuccess("""
                        {
                          "ok": false,
                          "error": "invalid_auth"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.verifyToken("bad-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid Slack token.");
        fixture.server.verify();
    }

    @Test
    void verifyTokenRejectsHttpErrorResponse() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/auth.test"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.verifyToken("bad-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid Slack token.");
        fixture.server.verify();
    }

    @Test
    void verifyTokenRejectsMissingWorkspaceInformation() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/auth.test"))
                .andRespond(withSuccess("""
                        {
                          "ok": true,
                          "team": "",
                          "team_id": ""
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.verifyToken("xoxb-token"))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Slack auth test response is missing workspace information.");
        fixture.server.verify();
    }

    private SlackClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SlackClient client = new SlackClient(
                new SlackProperties("https://slack.test/api/auth.test"),
                builder.build()
        );
        return new SlackClientFixture(client, server);
    }

    private record SlackClientFixture(SlackClient client, MockRestServiceServer server) {
    }
}
