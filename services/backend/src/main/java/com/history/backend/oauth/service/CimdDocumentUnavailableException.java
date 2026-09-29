package com.history.backend.oauth.service;

// CIMD 문서 조회가 일시적으로 실패했다는 뜻(타임아웃·5xx 등) — 이미 저장된 그림자 행이 있으면 그걸로 인증을 계속 진행한다.
public class CimdDocumentUnavailableException extends RuntimeException {

    public CimdDocumentUnavailableException(String message) {
        super(message);
    }

    public CimdDocumentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
