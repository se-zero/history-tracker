package com.history.pipeline_worker.collection;

// 수집용 Authorization 헤더 조립. GitHub·Jira·Slack은 Bearer 토큰, Discord는 앱 전체가 공유하는 봇 토큰(Bot)을 쓴다.
public final class AuthHeaders {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String BOT_PREFIX = "Bot ";

    private AuthHeaders() {
    }

    public static String bearer(String token) {
        return token.startsWith(BEARER_PREFIX) ? token : BEARER_PREFIX + token;
    }

    public static String bot(String token) {
        return token.startsWith(BOT_PREFIX) ? token : BOT_PREFIX + token;
    }
}
