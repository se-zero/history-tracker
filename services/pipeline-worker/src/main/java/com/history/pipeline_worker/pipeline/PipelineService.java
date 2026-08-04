package com.history.pipeline_worker.pipeline;

import com.history.pipeline_worker.collection.CollectionProvider;
import com.history.pipeline_worker.collection.ProjectCollectionContext;
import com.history.pipeline_worker.collection.SourceCollectorRegistry;
import com.history.pipeline_worker.dto.RawFetchRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;

/**
 * 수집 오케스트레이션 — provider별 수집은 {@code source.{provider}}의 SourceCollector가 소유하고,
 * 여기서는 "어떤 provider를 어떤 순서로 돌릴지"만 결정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineService {

    private final SourceCollectorRegistry collectors;

    // 프로젝트 컨텍스트에 담긴 provider를 순차 수집한다. 한 provider가 실패하면 예외가 전파돼
    // 이후 provider는 돌지 않는다 — 그 사이 checkpoint는 이미 전진했으므로 재시도해도 중복만 생긴다.
    public CollectionResult collectIncremental(ProjectCollectionContext context) {
        Map<CollectionProvider, Integer> published = new EnumMap<>(CollectionProvider.class);
        context.requests().forEach((provider, request) ->
                published.put(provider, collect(context.projectId(), provider, request)));
        return new CollectionResult(published);
    }

    public int collect(String projectId, CollectionProvider provider, RawFetchRequest request) {
        return collectors.get(provider).collect(projectId, request);
    }
}
