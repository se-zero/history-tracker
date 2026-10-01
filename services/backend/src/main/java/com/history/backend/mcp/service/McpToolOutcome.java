package com.history.backend.mcp.service;

// MCP 도구 실행 결과 — 에이전트가 문구를 읽고 다음 행동을 정하므로 예외 대신 (텍스트, 오류 여부)로 돌려준다
public record McpToolOutcome(String text, boolean isError) {
}
