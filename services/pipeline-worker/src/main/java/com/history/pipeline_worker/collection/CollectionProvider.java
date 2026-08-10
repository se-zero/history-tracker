package com.history.pipeline_worker.collection;

import java.util.Arrays;
import java.util.Optional;

public enum CollectionProvider {
    GITHUB("github"),
    JIRA("jira"),
    SLACK("slack"),
    LINEAR("linear"),
    // PipelineService가 선언 순서(EnumMap)로 순차 수집하므로 신규 provider는 항상 마지막에 추가한다
    // — 앞 provider가 실패해도 이후 수집 순서·장애 격리가 흔들리지 않는다.
    ASANA("asana"),
    CLICKUP("clickup");

    private final String value;

    CollectionProvider(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    // provider 문자열 → enum 조회 (미지원이면 Optional.empty())
    public static Optional<CollectionProvider> find(String value) {
        return Arrays.stream(values())
                .filter(provider -> provider.value.equals(value))
                .findFirst();
    }

    // 경로 변수 등 외부 입력 → enum (미지원이면 예외)
    public static CollectionProvider fromPath(String value) {
        return find(value)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported collection provider: " + value));
    }
}
