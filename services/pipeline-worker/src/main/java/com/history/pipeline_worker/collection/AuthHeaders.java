package com.history.pipeline_worker.collection;

// 수집용 Authorization 헤더 조립. GitHub·Jira·Slack 모두 Bearer 토큰을 쓴다.
public final class AuthHeaders {

    private static final String BEARER_PREFIX = "Bearer ";

    private AuthHeaders() {
    }

    public static String bearer(String token) {
        return token.startsWith(BEARER_PREFIX) ? token : BEARER_PREFIX + token;
    }
}
