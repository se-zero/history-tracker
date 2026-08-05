package com.history.pipeline_worker.collection;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// provider → SourceCollector 조회. Spring이 주입한 구현체 목록으로 구성돼, 새 소스는 빈 등록만으로 편입된다.
@Component
public class SourceCollectorRegistry {

    private final Map<CollectionProvider, SourceCollector> collectors;

    public SourceCollectorRegistry(List<SourceCollector> collectors) {
        Map<CollectionProvider, SourceCollector> byProvider = new EnumMap<>(CollectionProvider.class);
        for (SourceCollector collector : collectors) {
            SourceCollector duplicate = byProvider.put(collector.provider(), collector);
            if (duplicate != null) {
                throw new IllegalStateException("Duplicate SourceCollector for provider: " + collector.provider());
            }
        }
        this.collectors = Collections.unmodifiableMap(byProvider);
    }

    public Optional<SourceCollector> find(CollectionProvider provider) {
        return Optional.ofNullable(collectors.get(provider));
    }

    public SourceCollector get(CollectionProvider provider) {
        return find(provider).orElseThrow(() ->
                new IllegalStateException("No SourceCollector registered for provider: " + provider));
    }
}
