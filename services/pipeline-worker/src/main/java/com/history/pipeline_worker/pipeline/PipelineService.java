package com.history.pipeline_worker.pipeline;

import com.history.pipeline_worker.collection.CollectionProvider;
import com.history.pipeline_worker.collection.CollectionRunContext;
import com.history.pipeline_worker.collection.IntegrationDisconnectedException;
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
    private final CollectionRunContext runContext;

    // 프로젝트 컨텍스트에 담긴 provider를 순차 수집한다. 한 provider가 실패하면 예외가 전파돼
    // 이후 provider는 돌지 않는다 — 그 사이 checkpoint는 이미 전진했으므로 재시도해도 중복만 생긴다.
    // 단 연동이 해제된 provider는 실패가 아니라 중단이다 — 결과에서 빼고(0건 수집으로 기록하지 않음)
    // 해제와 무관한 나머지 provider는 계속 돈다.
    public CollectionResult collectIncremental(ProjectCollectionContext context) {
        Map<CollectionProvider, Integer> published = new EnumMap<>(CollectionProvider.class);
        context.requests().forEach((provider, request) -> {
            try {
                published.put(provider, collect(context.projectId(), provider, request));
            } catch (IntegrationDisconnectedException exception) {
                log.info("연동이 해제돼 수집을 멈춤: projectId={}, provider={}", context.projectId(), provider.value());
            }
        });
        return new CollectionResult(published);
    }

    // 요청에 실린 연동 id를 collect 동안의 수집 범위로 연다 — 발행·커서 쓰기가 모두 이 스레드에서 일어나
    // 수집기에 id를 넘기지 않아도 EventPublisher·CheckpointService가 같은 범위를 본다.
    public int collect(String projectId, CollectionProvider provider, RawFetchRequest request) {
        try (var scope = runContext.open(request.integrationId())) {
            return collectors.get(provider).collect(projectId, request);
        }
    }
}
