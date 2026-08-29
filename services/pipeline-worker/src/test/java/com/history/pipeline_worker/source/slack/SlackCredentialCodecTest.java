package com.history.pipeline_worker.source.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SlackCredentialCodec: 복호화된 Slack 자격증명 문자열에서 user 토큰을 추출.
 * JSON 파싱 성공 시 user_token 필드, 파싱 실패(또는 루트가 object가 아닌 경우) 시 평문 폴백.
 * ObjectMapper를 테스트와 공유하지 않고 입력/출력 문자열만 대조한다.
 */
class SlackCredentialCodecTest {

    private SlackCredentialCodec codec;

    @BeforeEach
    void setUp() {
        codec = new SlackCredentialCodec();
    }

    @Test
    @DisplayName("JSON 객체에 user_token이 있으면 그 값을 반환하고 bot_token은 무시한다")
    void userToken_jsonCredential_returnsUserToken() {
        assertThat(codec.userToken("{\"user_token\":\"xoxp-user\",\"bot_token\":\"xoxb-bot\"}"))
                .isEqualTo("xoxp-user");
    }

    @Test
    @DisplayName("평문 레거시 토큰은 파싱이 실패하므로 입력 전체를 그대로 반환한다")
    void userToken_legacyPlaintext_returnsAsIs() {
        assertThat(codec.userToken("xoxp-legacy"))
                .isEqualTo("xoxp-legacy");
    }

    @Test
    @DisplayName("JSON 객체인데 user_token 필드가 없으면 IllegalStateException — 객체 전체를 토큰으로 쓰면 안 된다")
    void userToken_jsonMissingUserToken_throwsIllegalStateException() {
        assertThatThrownBy(() -> codec.userToken("{\"bot_token\":\"xoxb-bot\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing Slack credential field: user_token");
    }

    @Test
    @DisplayName("JSON 객체의 user_token이 blank이면 IllegalStateException")
    void userToken_jsonBlankUserToken_throwsIllegalStateException() {
        assertThatThrownBy(() -> codec.userToken("{\"user_token\":\"   \",\"bot_token\":\"xoxb-bot\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing Slack credential field: user_token");
    }

    @Test
    @DisplayName("JSON 객체의 user_token이 null(명시적 JSON null)이면 IllegalStateException")
    void userToken_jsonNullUserToken_throwsIllegalStateException() {
        assertThatThrownBy(() -> codec.userToken("{\"user_token\":null,\"bot_token\":\"xoxb-bot\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing Slack credential field: user_token");
    }

    @Test
    @DisplayName("깨진 JSON이면 파싱 실패로 입력 전체를 평문 폴백으로 반환한다 — Jira처럼 예외를 던지면 안 된다")
    void userToken_malformedJson_fallsBackToPlaintext() {
        assertThat(codec.userToken("not-json{"))
                .isEqualTo("not-json{");
    }

    @Test
    @DisplayName("루트가 JSON 배열이면(object가 아님) 입력 전체를 평문 폴백으로 반환한다")
    void userToken_jsonArray_fallsBackToPlaintext() {
        assertThat(codec.userToken("[]"))
                .isEqualTo("[]");
    }
}
