package com.history.backend.oauth.service;

// CIMD 문서 자체가 영구적으로 무효하다는 뜻 — 재시도해도 같은 결과이므로 클라이언트를 모르는 것으로 처리한다.
public class CimdDocumentInvalidException extends RuntimeException {

    public CimdDocumentInvalidException(String message) {
        super(message);
    }

    public CimdDocumentInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}
