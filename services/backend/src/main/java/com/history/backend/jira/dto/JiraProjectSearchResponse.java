package com.history.backend.jira.dto;

import java.util.List;

// GET .../rest/api/3/project/search 응답 — 100개를 넘는 프로젝트는 잘린다(페이징 미구현)
public record JiraProjectSearchResponse(
        List<Value> values
) {

    public record Value(String key, String name) {
    }
}
