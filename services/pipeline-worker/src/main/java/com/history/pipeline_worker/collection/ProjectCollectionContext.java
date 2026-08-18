package com.history.pipeline_worker.collection;

import com.history.pipeline_worker.dto.RawFetchRequest;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * 한 프로젝트에서 이번에 수집할 provider별 요청 묶음.
 *
 * <p>수집 순서는 {@link CollectionProvider} 선언 순서를 따른다(EnumMap) — provider 수가 늘어도
 * 실행 순서가 흔들리지 않게 하기 위함이다.</p>
 */
public record ProjectCollectionContext(
        String projectId,
        Map<CollectionProvider, RawFetchRequest> requests
) {

    public ProjectCollectionContext {
        Map<CollectionProvider, RawFetchRequest> copy = new EnumMap<>(CollectionProvider.class);
        if (requests != null) {
            copy.putAll(requests);
        }
        requests = Collections.unmodifiableMap(copy);
    }

    public Optional<RawFetchRequest> request(CollectionProvider provider) {
        return Optional.ofNullable(requests.get(provider));
    }

    public ProjectCollectionContext with(CollectionProvider provider, RawFetchRequest request) {
        Map<CollectionProvider, RawFetchRequest> updated = new EnumMap<>(CollectionProvider.class);
        updated.putAll(requests);
        updated.put(provider, request);
        return new ProjectCollectionContext(projectId, updated);
    }

    public ProjectCollectionContext without(CollectionProvider provider) {
        Map<CollectionProvider, RawFetchRequest> updated = new EnumMap<>(CollectionProvider.class);
        updated.putAll(requests);
        updated.remove(provider);
        return new ProjectCollectionContext(projectId, updated);
    }
}
