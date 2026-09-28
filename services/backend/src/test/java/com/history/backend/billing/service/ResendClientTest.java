package com.history.backend.billing.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.history.backend.billing.ResendProperties;
import com.history.backend.common.error.BadGatewayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("ResendClient: 안내 메일 발송")
class ResendClientTest {

    @Test
    @DisplayName("발신 주소·답장 주소·받는 사람을 JSON으로 보낸다")
    void sendPostsEmail() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer re_test"))
                .andExpect(jsonPath("$.from").value("whycode <billing@mail.why-code.com>"))
                .andExpect(jsonPath("$.reply_to").value("contact@why-code.com"))
                .andExpect(jsonPath("$.to[0]").value("payer@example.com"))
                .andExpect(jsonPath("$.subject").value("제목"))
                .andExpect(jsonPath("$.text").value("본문"))
                .andRespond(withSuccess("{\"id\":\"email_1\"}", MediaType.APPLICATION_JSON));

        fixture.client.send(new OutboundEmail("payer@example.com", "제목", "본문"));

        fixture.server.verify();
    }

    @Test
    @DisplayName("4xx는 BadGatewayException이고 응답 본문은 메시지에 없다")
    void sendMapsHttpFailureWithoutResponseBody() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.resend.com/emails"))
                .andRespond(withBadRequest().body("{\"message\":\"secret-body\"}"));

        assertThatThrownBy(() -> fixture.client.send(new OutboundEmail("payer@example.com", "제목", "본문")))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Resend email request failed.")
                .hasMessageNotContaining("secret-body");
        fixture.server.verify();
    }

    @Test
    @DisplayName("API 키가 비면 호출하지 않는다")
    void blankApiKeyFailsBeforeCallingResend() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.resend.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ResendClient client = new ResendClient(
                new ResendProperties("", "from", "reply"),
                builder.build());

        assertThatThrownBy(() -> client.send(new OutboundEmail("payer@example.com", "제목", "본문")))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Resend API key is not configured.");
        server.verify();
    }

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.resend.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ResendClient client = new ResendClient(
                new ResendProperties("re_test", "whycode <billing@mail.why-code.com>", "contact@why-code.com"),
                builder.build());
        return new Fixture(client, server);
    }

    private record Fixture(ResendClient client, MockRestServiceServer server) {
    }
}
