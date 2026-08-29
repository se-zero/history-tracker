package com.history.pipeline_worker.source.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 복호화된 Slack 자격증명 문자열에서 user 토큰을 추출한다.
 * Jira와 달리 파싱 실패(또는 루트가 object가 아닌 경우)는 예외 없이 평문 폴백으로 처리한다 —
 * backend가 JSON 포맷 쓰기를 시작해도(S2-b) 이전 평문 credential이 남아 있는 worker가
 * 깨지지 않아야 하기 때문이다.
 */
public class SlackCredentialCodec {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // object로 파싱된 뒤에만 user_token을 필수로 요구한다.
    // 평문이나 깨진 JSON은 그대로 반환해 레거시 연동을 보존한다.
    public String userToken(String decryptedPlaintext) {
        JsonNode root;
        try {
            root = objectMapper.readTree(decryptedPlaintext);
        } catch (Exception e) {
            return decryptedPlaintext;
        }
        if (!root.isObject()) {
            return decryptedPlaintext;
        }
        JsonNode userTokenNode = root.get("user_token");
        if (userTokenNode != null && userTokenNode.isTextual() && !userTokenNode.asText().isBlank()) {
            return userTokenNode.asText();
        }
        // JSON 객체인데 user_token이 없거나 유효하지 않으면 객체 전체를 토큰으로 사용하면 안 된다.
        throw new IllegalStateException("Missing Slack credential field: user_token");
    }
}
