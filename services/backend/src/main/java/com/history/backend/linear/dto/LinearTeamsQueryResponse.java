package com.history.backend.linear.dto;

import java.util.List;

// POST /graphql (teams 쿼리) 응답 — Linear는 오류도 HTTP 200으로 내려주므로
// errors를 함께 파싱해 호출부가 성공/실패를 가릴 수 있게 한다.
public record LinearTeamsQueryResponse(
        Data data,
        List<GraphQlError> errors
) {

    public record Data(Teams teams) {
    }

    public record Teams(List<Node> nodes) {
    }

    public record Node(String id, String name) {
    }

    public record GraphQlError(String message) {
    }
}
