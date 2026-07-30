package com.history.backend.integration.service;

// OAuth state 토큰 검증 실패 (형식 오류·서명 위조·만료·provider 불일치)
public class OAuthStateException extends RuntimeException {

    public OAuthStateException(String message) {
        super(message);
    }
}
