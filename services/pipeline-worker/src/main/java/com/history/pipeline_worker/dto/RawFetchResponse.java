package com.history.pipeline_worker.dto;

import java.time.Instant;
import java.util.Map;

public record RawFetchResponse(
        // 데이터 출처. "GITHUB" | "JIRA" | "SLACK"
        String source,

        // 수집 시각 (ISO-8601)
        Instant fetchedAt,

        // 소스 API에서 받은 원본 JSON. 소스마다 키 구조가 다름 (정규화 전 단계)
        Map<String, Object> data
) {}
