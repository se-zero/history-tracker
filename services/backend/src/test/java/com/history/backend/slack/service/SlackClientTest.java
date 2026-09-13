package com.history.backend.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.ZoneId;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.slack.SlackProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@DisplayName("SlackClient: Slack API 호출")
class SlackClientTest {

    // postEphemeralMarkdown 절단 안내 — 12,000자 초과 시에만 붙는다
    private static final String TRUNCATION_NOTICE =
            "\n\n답변이 길어 일부만 표시했어요. 대시보드에서 질문하면 전체 답변을 볼 수 있어요.";

    @Test
    @DisplayName("Slack code 교환 성공 → 워크스페이스·user token·authed user id 반환, bot 토큰 없으면 null")
    void exchangeCodeReturnsWorkspaceAndTokens() {
        SlackClientFixture fixture = fixture();
        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("client_id", "test-client-id");
        expectedForm.add("client_secret", "test-client-secret");
        expectedForm.add("code", "auth-code");
        expectedForm.add("redirect_uri", "https://slack.test/callback");

        fixture.server.expect(once(), requestTo("https://slack.test/api/oauth.v2.access"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess("""
                        {
                          "ok": true,
                          "team": { "id": "T123", "name": "Acme" },
                          "authed_user": { "id": "U123", "access_token": "xoxp-token" }
                        }
                        """, MediaType.APPLICATION_JSON));

        SlackClient.SlackWorkspace result = fixture.client.exchangeCode("auth-code");

        assertThat(result.id()).isEqualTo("T123");
        assertThat(result.name()).isEqualTo("Acme");
        assertThat(result.userToken()).isEqualTo("xoxp-token");
        assertThat(result.authedUserId()).isEqualTo("U123");
        assertThat(result.botToken()).isNull();
        fixture.server.verify();
    }

    @Test
    @DisplayName("루트 access_token(bot 토큰)이 있으면 botToken 필드로 매핑된다")
    void exchangeCodeReturnsBotTokenWhenPresent() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/oauth.v2.access"))
                .andRespond(withSuccess("""
                        {
                          "ok": true,
                          "access_token": "xoxb-bot",
                          "team": { "id": "T123", "name": "Acme" },
                          "authed_user": { "id": "U123", "access_token": "xoxp-token" }
                        }
                        """, MediaType.APPLICATION_JSON));

        SlackClient.SlackWorkspace result = fixture.client.exchangeCode("auth-code");

        assertThat(result.userToken()).isEqualTo("xoxp-token");
        assertThat(result.botToken()).isEqualTo("xoxb-bot");
        assertThat(result.authedUserId()).isEqualTo("U123");
        fixture.server.verify();
    }

    @Test
    @DisplayName("authed_user.id 누락 응답 → BadGatewayException 발생")
    void exchangeCodeRejectsMissingAuthedUserId() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/oauth.v2.access"))
                .andRespond(withSuccess("""
                        {
                          "ok": true,
                          "team": { "id": "T123", "name": "Acme" },
                          "authed_user": { "access_token": "xoxp-token" }
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Slack OAuth response is missing authed user id.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("Slack ok:false 응답 → UnauthorizedException 발생")
    void exchangeCodeRejectsSlackErrorResponse() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/oauth.v2.access"))
                .andRespond(withSuccess("""
                        {
                          "ok": false,
                          "error": "invalid_code"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("bad-code"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid Slack authorization code.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("HTTP 오류 응답 → UnauthorizedException 발생")
    void exchangeCodeRejectsHttpErrorResponse() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/oauth.v2.access"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.exchangeCode("bad-code"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid Slack authorization code.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("authed_user.access_token 없는 응답(봇 토큰만 온 경우) → BadGatewayException 발생")
    void exchangeCodeRejectsMissingUserAccessToken() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/oauth.v2.access"))
                .andRespond(withSuccess("""
                        {
                          "ok": true,
                          "team": { "id": "T123", "name": "Acme" }
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Slack OAuth response is missing user access token.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("워크스페이스 정보 누락 응답 → BadGatewayException 발생")
    void exchangeCodeRejectsMissingWorkspaceInformation() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/oauth.v2.access"))
                .andRespond(withSuccess("""
                        {
                          "ok": true,
                          "team": { "id": "", "name": "" },
                          "authed_user": { "id": "U123", "access_token": "xoxp-token" }
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Slack OAuth response is missing workspace information.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("token 폐기 성공(ok:true) → true 반환")
    void revokeReturnsTrueWhenSlackAcknowledgesOk() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/auth.revoke"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        { "ok": true }
                        """, MediaType.APPLICATION_JSON));

        boolean result = fixture.client.revoke("xoxp-token");

        assertThat(result).isTrue();
        fixture.server.verify();
    }

    @Test
    @DisplayName("문서에 없는 에러 코드는 여전히 실패로 처리")
    void revokeReturnsFalseForUnknownError() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/auth.revoke"))
                .andRespond(withSuccess("""
                        { "ok": false, "error": "already_revoked" }
                        """, MediaType.APPLICATION_JSON));

        boolean result = fixture.client.revoke("xoxp-token");

        assertThat(result).isFalse();
        fixture.server.verify();
    }

    @Test
    @DisplayName("token 폐기 응답 error:invalid_auth(이미 무효화된 토큰) → true 반환")
    void revokeReturnsTrueWhenSlackReportsInvalidAuth() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/auth.revoke"))
                .andRespond(withSuccess("""
                        { "ok": false, "error": "invalid_auth" }
                        """, MediaType.APPLICATION_JSON));

        boolean result = fixture.client.revoke("xoxp-token");

        assertThat(result).isTrue();
        fixture.server.verify();
    }

    @Test
    @DisplayName("token 폐기 응답 error:token_revoked(이미 무효화된 토큰) → true 반환")
    void revokeReturnsTrueWhenSlackReportsTokenRevoked() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/auth.revoke"))
                .andRespond(withSuccess("""
                        { "ok": false, "error": "token_revoked" }
                        """, MediaType.APPLICATION_JSON));

        boolean result = fixture.client.revoke("xoxp-token");

        assertThat(result).isTrue();
        fixture.server.verify();
    }

    @Test
    @DisplayName("token 폐기 응답 error:token_expired(이미 무효화된 토큰) → true 반환")
    void revokeReturnsTrueWhenSlackReportsTokenExpired() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/auth.revoke"))
                .andRespond(withSuccess("""
                        { "ok": false, "error": "token_expired" }
                        """, MediaType.APPLICATION_JSON));

        boolean result = fixture.client.revoke("xoxp-token");

        assertThat(result).isTrue();
        fixture.server.verify();
    }

    @Test
    @DisplayName("token 폐기 ok:false인데 error 필드가 없는 응답 → 예외 없이 false 반환")
    void revokeReturnsFalseWhenSlackOmitsErrorField() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/auth.revoke"))
                .andRespond(withSuccess("""
                        { "ok": false }
                        """, MediaType.APPLICATION_JSON));

        boolean result = fixture.client.revoke("xoxp-token");

        assertThat(result).isFalse();
        fixture.server.verify();
    }

    @Test
    @DisplayName("token 폐기 요청이 실패해도 예외를 던지지 않고 false를 반환한다")
    void revokeReturnsFalseWhenRequestFails() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/auth.revoke"))
                .andRespond(withServerError());

        boolean result = fixture.client.revoke("xoxp-token");

        assertThat(result).isFalse();
        fixture.server.verify();
    }

    @Test
    @DisplayName("auth.test 성공(ok+user_id) → user_id 반환, form token= 로 호출 (SlackProperties URL 아님)")
    void authTestReturnsUserIdWhenOk() {
        SlackClientFixture fixture = fixture();
        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("token", "xoxp-user");
        fixture.server.expect(once(), requestTo("https://slack.com/api/auth.test"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess("""
                        { "ok": true, "user_id": "U123XYZ" }
                        """, MediaType.APPLICATION_JSON));

        String userId = fixture.client.authTest("xoxp-user");

        assertThat(userId).isEqualTo("U123XYZ");
        fixture.server.verify();
    }

    @Test
    @DisplayName("auth.test ok:false → 예외 없이 null (커맨드 전체를 죽이지 않는다)")
    void authTestReturnsNullWhenSlackReportsNotOk() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.com/api/auth.test"))
                .andRespond(withSuccess("""
                        { "ok": false, "error": "invalid_auth" }
                        """, MediaType.APPLICATION_JSON));

        assertThat(fixture.client.authTest("xoxp-user")).isNull();
        fixture.server.verify();
    }

    @Test
    @DisplayName("auth.test HTTP 오류 → 예외 없이 null")
    void authTestReturnsNullWhenRequestFails() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.com/api/auth.test"))
                .andRespond(withServerError());

        assertThat(fixture.client.authTest("xoxp-user")).isNull();
        fixture.server.verify();
    }

    @Test
    @DisplayName("verifyToken 성공 → Slack.com auth.test 에 form token= 로 호출하고 team/user 매핑 "
            + "(앞뒤 공백은 trim, SlackProperties URL이 아님, 반환값에 토큰 없음)")
    void verifyTokenReturnsWorkspaceAndUserWhenOk() {
        SlackClientFixture fixture = fixture();
        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("token", "xoxp-user");
        fixture.server.expect(once(), requestTo("https://slack.com/api/auth.test"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess("""
                        {
                          "ok": true,
                          "team": "Acme",
                          "team_id": "T123",
                          "user_id": "U123"
                        }
                        """, MediaType.APPLICATION_JSON));

        SlackClient.SlackVerifiedUser result = fixture.client.verifyToken("  xoxp-user  ");

        assertThat(result).isEqualTo(new SlackClient.SlackVerifiedUser("T123", "Acme", "U123"));
        fixture.server.verify();
    }

    @Test
    @DisplayName("verifyToken — xoxb- 접두사는 HTTP 없이 Unauthorized (봇 토큰 거절)")
    void verifyTokenRejectsBotTokenPrefixWithoutCallingSlack() {
        SlackClientFixture fixture = fixture();

        assertThatThrownBy(() -> fixture.client.verifyToken("xoxb-bot"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid Slack token.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("verifyToken — xoxe- 접두사는 HTTP 없이 Unauthorized")
    void verifyTokenRejectsExchangeTokenPrefixWithoutCallingSlack() {
        SlackClientFixture fixture = fixture();

        assertThatThrownBy(() -> fixture.client.verifyToken("xoxe-exchange"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid Slack token.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("verifyToken — xoxp- 가 아니면 HTTP 없이 Unauthorized (xoxp 하이픈 없는 값 포함)")
    void verifyTokenRejectsTokenThatDoesNotStartWithUserPrefixWithoutCallingSlack() {
        SlackClientFixture fixture = fixture();

        assertThatThrownBy(() -> fixture.client.verifyToken("not-a-slack-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid Slack token.");
        assertThatThrownBy(() -> fixture.client.verifyToken("xoxp"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid Slack token.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("verifyToken ok:false → Unauthorized. authTest는 같은 응답을 삼키고 null "
            + "(커맨드 백필과 BYO 검증 계약이 갈라진다)")
    void verifyTokenThrowsUnauthorizedWhenSlackReportsNotOkUnlikeAuthTest() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.com/api/auth.test"))
                .andRespond(withSuccess("""
                        { "ok": false, "error": "invalid_auth" }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.verifyToken("xoxp-user"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid Slack token.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("verifyToken HTTP 4xx → Unauthorized")
    void verifyTokenThrowsUnauthorizedOnHttpClientError() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.com/api/auth.test"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.verifyToken("xoxp-user"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid Slack token.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("verifyToken HTTP 5xx → BadGateway (authTest가 삼키는 것과 다르고, "
            + "exchangeCode가 4xx/5xx를 모두 Unauthorized로 묶는 것과도 다르다). 메시지에 토큰 원문 없음")
    void verifyTokenThrowsBadGatewayOnHttpServerErrorUnlikeAuthTest() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.com/api/auth.test"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.verifyToken("xoxp-user"))
                .isInstanceOf(BadGatewayException.class)
                .hasMessageNotContaining("xoxp-user");
        fixture.server.verify();
    }

    @Test
    @DisplayName("verifyToken — ok:true 여도 bot_id가 있으면 Unauthorized (봇 토큰 거절)")
    void verifyTokenRejectsResponseWithBotId() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.com/api/auth.test"))
                .andRespond(withSuccess("""
                        {
                          "ok": true,
                          "team": "Acme",
                          "team_id": "T123",
                          "user_id": "U123",
                          "bot_id": "B123"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.verifyToken("xoxp-user"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid Slack token.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("verifyToken — team_id 없음 → BadGateway (workspace 정보 없음)")
    void verifyTokenRejectsMissingTeamId() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.com/api/auth.test"))
                .andRespond(withSuccess("""
                        {
                          "ok": true,
                          "team": "Acme",
                          "team_id": "",
                          "user_id": "U123"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.verifyToken("xoxp-user"))
                .isInstanceOf(BadGatewayException.class)
                .hasMessageNotContaining("xoxp-user");
        fixture.server.verify();
    }

    @Test
    @DisplayName("verifyToken — team 없음 → BadGateway (workspace 정보 없음)")
    void verifyTokenRejectsMissingTeamName() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.com/api/auth.test"))
                .andRespond(withSuccess("""
                        {
                          "ok": true,
                          "team": "",
                          "team_id": "T123",
                          "user_id": "U123"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.verifyToken("xoxp-user"))
                .isInstanceOf(BadGatewayException.class)
                .hasMessageNotContaining("xoxp-user");
        fixture.server.verify();
    }

    @Test
    @DisplayName("verifyToken — user_id 없음 → BadGateway (authed user id 없음)")
    void verifyTokenRejectsMissingUserId() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.com/api/auth.test"))
                .andRespond(withSuccess("""
                        {
                          "ok": true,
                          "team": "Acme",
                          "team_id": "T123",
                          "user_id": ""
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.verifyToken("xoxp-user"))
                .isInstanceOf(BadGatewayException.class)
                .hasMessageNotContaining("xoxp-user");
        fixture.server.verify();
    }

    @Test
    @DisplayName("postEphemeral — JSON response_type=ephemeral 을 response_url에 POST")
    void postEphemeralPostsEphemeralJsonToResponseUrl() {
        SlackClientFixture fixture = fixture();
        String responseUrl = "https://hooks.slack.com/commands/T123/resp";
        fixture.server.expect(once(), requestTo(responseUrl))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        { "response_type": "ephemeral", "text": "찾는 중" }
                        """))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        fixture.client.postEphemeral(responseUrl, "찾는 중");

        fixture.server.verify();
    }

    @Test
    @DisplayName("postEphemeral HTTP 실패 → 예외를 던지지 않는다")
    void postEphemeralSwallowsHttpError() {
        SlackClientFixture fixture = fixture();
        String responseUrl = "https://hooks.slack.com/commands/T123/resp";
        fixture.server.expect(once(), requestTo(responseUrl))
                .andRespond(withServerError());

        assertThatCode(() -> fixture.client.postEphemeral(responseUrl, "찾는 중"))
                .doesNotThrowAnyException();
        fixture.server.verify();
    }

    @Test
    @DisplayName("postEphemeralMarkdown — JSON response_type=ephemeral, text와 blocks[0](type=markdown)의 text가 같은 본문")
    void postEphemeralMarkdownPostsSameBodyInTextAndMarkdownBlock() {
        SlackClientFixture fixture = fixture();
        String responseUrl = "https://hooks.slack.com/commands/T123/resp";
        String markdown = "## 답변\n\n**bold** 텍스트입니다.";
        fixture.server.expect(once(), requestTo(responseUrl))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.response_type").value("ephemeral"))
                .andExpect(jsonPath("$.text").value(markdown))
                .andExpect(jsonPath("$.blocks[0].type").value("markdown"))
                .andExpect(jsonPath("$.blocks[0].text").value(markdown))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        fixture.client.postEphemeralMarkdown(responseUrl, markdown);

        fixture.server.verify();
    }

    @Test
    @DisplayName("postEphemeralMarkdown — 12,000자 초과 시 잘라내고 안내 문구를 붙이며 최종 길이는 12,000자 이하")
    void postEphemeralMarkdownTruncatesBodyOver12000CharsAndAppendsNotice() throws Exception {
        SlackClientFixture fixture = fixture();
        String responseUrl = "https://hooks.slack.com/commands/T123/resp";
        String markdown = "a".repeat(13_000);
        ObjectMapper objectMapper = new ObjectMapper();
        fixture.server.expect(once(), requestTo(responseUrl))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request).getBodyAsString();
                    JsonNode json = objectMapper.readTree(body);
                    String text = json.path("text").asText();
                    String blockText = json.path("blocks").get(0).path("text").asText();
                    assertThat(blockText).isEqualTo(text);
                    assertThat(text).endsWith(TRUNCATION_NOTICE);
                    assertThat(text).startsWith(markdown.substring(0, 100));
                    assertThat(text.length()).isLessThanOrEqualTo(12_000);
                })
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        fixture.client.postEphemeralMarkdown(responseUrl, markdown);

        fixture.server.verify();
    }

    @Test
    @DisplayName("postEphemeralMarkdown — 절단은 한도 안의 마지막 줄바꿈에서 한다 (줄 중간에서 끊지 않는다)")
    void postEphemeralMarkdownTruncatesAtLastLineBreakWithinLimit() throws Exception {
        SlackClientFixture fixture = fixture();
        String responseUrl = "https://hooks.slack.com/commands/T123/resp";
        // 99자 + 줄바꿈 = 100자짜리 줄 130개 → 13,000자
        String markdown = ("a".repeat(99) + "\n").repeat(130);
        int contentLimit = 12_000 - TRUNCATION_NOTICE.length();
        ObjectMapper objectMapper = new ObjectMapper();
        fixture.server.expect(once(), requestTo(responseUrl))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request).getBodyAsString();
                    String text = objectMapper.readTree(body).path("text").asText();
                    assertThat(text).endsWith(TRUNCATION_NOTICE);
                    String content = text.substring(0, text.length() - TRUNCATION_NOTICE.length());
                    // 줄 경계에서 잘렸다면 모든 줄이 온전한 99자다
                    assertThat(content.split("\n")).allSatisfy(line -> assertThat(line).hasSize(99));
                    // 한도 안의 "마지막" 줄바꿈이어야 한다 — 한 줄(100자) 이상 일찍 자르면 안 된다
                    assertThat(content.length()).isLessThanOrEqualTo(contentLimit).isGreaterThan(contentLimit - 100);
                })
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        fixture.client.postEphemeralMarkdown(responseUrl, markdown);

        fixture.server.verify();
    }

    @Test
    @DisplayName("postEphemeralMarkdown — 정확히 12,000자면 자르지 않고 안내 문구도 붙이지 않는다")
    void postEphemeralMarkdownDoesNotTruncateWhenExactlyAtLimit() {
        SlackClientFixture fixture = fixture();
        String responseUrl = "https://hooks.slack.com/commands/T123/resp";
        String markdown = "a".repeat(12_000);
        fixture.server.expect(once(), requestTo(responseUrl))
                .andExpect(jsonPath("$.blocks[0].text").value(markdown))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        fixture.client.postEphemeralMarkdown(responseUrl, markdown);

        fixture.server.verify();
    }

    @Test
    @DisplayName("postEphemeralMarkdown HTTP 실패 → 예외를 던지지 않는다")
    void postEphemeralMarkdownSwallowsHttpError() {
        SlackClientFixture fixture = fixture();
        String responseUrl = "https://hooks.slack.com/commands/T123/resp";
        fixture.server.expect(once(), requestTo(responseUrl))
                .andRespond(withServerError());

        assertThatCode(() -> fixture.client.postEphemeralMarkdown(responseUrl, "본문"))
                .doesNotThrowAnyException();
        fixture.server.verify();
    }

    @Test
    @DisplayName("postEphemeralMarkdown — Slack이 블록 페이로드를 거부(4xx)하면 같은 본문을 평문으로 다시 보낸다")
    void postEphemeralMarkdownFallsBackToPlainTextWhenBlocksRejected() {
        SlackClientFixture fixture = fixture();
        String responseUrl = "https://hooks.slack.com/commands/T123/resp";
        String markdown = "## 답변\n\n**bold**";
        fixture.server.expect(once(), requestTo(responseUrl))
                .andExpect(jsonPath("$.blocks[0].type").value("markdown"))
                .andRespond(withBadRequest().body("invalid_blocks"));
        // 재전송은 blocks 없는 평문 페이로드여야 한다 (strict: 여분 필드가 있으면 실패)
        fixture.server.expect(once(), requestTo(responseUrl))
                .andExpect(content().json("""
                        { "response_type": "ephemeral", "text": "## 답변\\n\\n**bold**" }
                        """, true))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatCode(() -> fixture.client.postEphemeralMarkdown(responseUrl, markdown))
                .doesNotThrowAnyException();
        fixture.server.verify();
    }

    @Test
    @DisplayName("postEphemeralMarkdown — 줄바꿈이 한도 근처(1,000자 안)에 없으면 줄 경계를 포기하고 한도에서 자른다")
    void postEphemeralMarkdownFallsBackToHardCutWhenLineBreakIsTooEarly() throws Exception {
        SlackClientFixture fixture = fixture();
        String responseUrl = "https://hooks.slack.com/commands/T123/resp";
        // 첫 줄 뒤에 줄바꿈 없는 긴 한 줄 — 줄 경계를 고집하면 "제목"만 남는다
        String markdown = "제목\n" + "a".repeat(13_000);
        int contentLimit = 12_000 - TRUNCATION_NOTICE.length();
        ObjectMapper objectMapper = new ObjectMapper();
        fixture.server.expect(once(), requestTo(responseUrl))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request).getBodyAsString();
                    String text = objectMapper.readTree(body).path("text").asText();
                    String content = text.substring(0, text.length() - TRUNCATION_NOTICE.length());
                    assertThat(content).startsWith("제목\na");
                    assertThat(content.length()).isEqualTo(contentLimit);
                })
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        fixture.client.postEphemeralMarkdown(responseUrl, markdown);

        fixture.server.verify();
    }

    @Test
    @DisplayName("postEphemeralMarkdown — 한도에서 자를 때 이모지(서로게이트 쌍) 사이를 가르지 않는다")
    void postEphemeralMarkdownDoesNotSplitSurrogatePairAtHardCut() throws Exception {
        SlackClientFixture fixture = fixture();
        String responseUrl = "https://hooks.slack.com/commands/T123/resp";
        int contentLimit = 12_000 - TRUNCATION_NOTICE.length();
        // 이모지의 앞 조각이 정확히 한도 마지막 자리(contentLimit - 1)에 오도록 배치한다
        String markdown = "a".repeat(contentLimit - 1) + "😀" + "b".repeat(2_000);
        ObjectMapper objectMapper = new ObjectMapper();
        fixture.server.expect(once(), requestTo(responseUrl))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request).getBodyAsString();
                    String text = objectMapper.readTree(body).path("text").asText();
                    String content = text.substring(0, text.length() - TRUNCATION_NOTICE.length());
                    assertThat(content).isEqualTo("a".repeat(contentLimit - 1));
                    assertThat(content.chars().anyMatch(c -> Character.isSurrogate((char) c))).isFalse();
                })
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        fixture.client.postEphemeralMarkdown(responseUrl, markdown);

        fixture.server.verify();
    }

    @Test
    @DisplayName("userTimezone — users.info 성공(ok+tz) → ZoneId 반환, form token=/user= 로 호출")
    void userTimezoneReturnsZoneIdWhenSlackRespondsOk() {
        SlackClientFixture fixture = fixture();
        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("token", "xoxp-user");
        expectedForm.add("user", "U123XYZ");
        fixture.server.expect(once(), requestTo("https://slack.com/api/users.info"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess("""
                        {
                          "ok": true,
                          "user": {
                            "id": "U123XYZ",
                            "tz": "Asia/Seoul",
                            "tz_label": "Korean Standard Time",
                            "tz_offset": 32400
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        ZoneId result = fixture.client.userTimezone("xoxp-user", "U123XYZ");

        assertThat(result).isEqualTo(ZoneId.of("Asia/Seoul"));
        fixture.server.verify();
    }

    @Test
    @DisplayName("userTimezone — users.info ok:false → 예외 없이 null")
    void userTimezoneReturnsNullWhenSlackReportsNotOk() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.com/api/users.info"))
                .andRespond(withSuccess("""
                        { "ok": false, "error": "user_not_found" }
                        """, MediaType.APPLICATION_JSON));

        assertThat(fixture.client.userTimezone("xoxp-user", "U123XYZ")).isNull();
        fixture.server.verify();
    }

    @Test
    @DisplayName("userTimezone — HTTP 오류 → 예외 없이 null")
    void userTimezoneReturnsNullWhenRequestFails() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.com/api/users.info"))
                .andRespond(withServerError());

        assertThat(fixture.client.userTimezone("xoxp-user", "U123XYZ")).isNull();
        fixture.server.verify();
    }

    @Test
    @DisplayName("userTimezone — tz 필드 없음 → null")
    void userTimezoneReturnsNullWhenTzFieldMissing() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.com/api/users.info"))
                .andRespond(withSuccess("""
                        { "ok": true, "user": { "id": "U123XYZ" } }
                        """, MediaType.APPLICATION_JSON));

        assertThat(fixture.client.userTimezone("xoxp-user", "U123XYZ")).isNull();
        fixture.server.verify();
    }

    @Test
    @DisplayName("userTimezone — tz가 잘못된 ZoneId 값(Not/AZone)이면 예외 없이 null")
    void userTimezoneReturnsNullWhenTzIsInvalidZoneId() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.com/api/users.info"))
                .andRespond(withSuccess("""
                        { "ok": true, "user": { "id": "U123XYZ", "tz": "Not/AZone" } }
                        """, MediaType.APPLICATION_JSON));

        assertThat(fixture.client.userTimezone("xoxp-user", "U123XYZ")).isNull();
        fixture.server.verify();
    }

    private SlackClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SlackClient client = new SlackClient(
                new SlackProperties(
                        "test-client-id",
                        "test-client-secret",
                        "https://slack.test/callback",
                        "channels:read,groups:read,channels:history,groups:history,users:read,users:read.email",
                        "commands",
                        "https://slack.test/oauth/v2/authorize",
                        "https://slack.test/api/oauth.v2.access",
                        "https://slack.test/api/auth.revoke"
                ),
                builder.build()
        );
        return new SlackClientFixture(client, server);
    }

    private record SlackClientFixture(SlackClient client, MockRestServiceServer server) {
    }
}
