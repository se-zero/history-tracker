package com.history.pipeline_worker.pipeline;

import com.history.pipeline_worker.collection.CollectionProvider;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

// provider별 발행 건수. provider가 늘어도 필드를 추가할 필요가 없게 맵으로 담는다.
public record CollectionResult(Map<CollectionProvider, Integer> published) {

    public CollectionResult {
        Map<CollectionProvider, Integer> copy = new EnumMap<>(CollectionProvider.class);
        if (published != null) {
            copy.putAll(published);
        }
        published = Collections.unmodifiableMap(copy);
    }

    public int of(CollectionProvider provider) {
        return published.getOrDefault(provider, 0);
    }

    public int total() {
        return published.values().stream().mapToInt(Integer::intValue).sum();
    }
}
