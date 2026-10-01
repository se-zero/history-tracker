package com.history.backend.oauth.service;

// 동의 결정 결과 — 거부면 티켓이 없다(null)
public record ConsentDecision(String redirectTo, String ticket) {
}
