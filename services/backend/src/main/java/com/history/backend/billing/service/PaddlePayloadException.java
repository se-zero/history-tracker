package com.history.backend.billing.service;

// Paddle 구독 데이터를 해석할 수 없을 때(JSON 깨짐·시각 형식 오류) 파서가 던진다. 호출부가 자기 맥락에 맞는
// 오류로 바꾼다 — 웹훅은 400, Paddle API 호출은 502.
public class PaddlePayloadException extends RuntimeException {

    public PaddlePayloadException(String message) {
        super(message);
    }
}
