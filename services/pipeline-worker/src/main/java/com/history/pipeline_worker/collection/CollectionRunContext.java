package com.history.pipeline_worker.collection;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 지금 스레드의 수집 실행이 어느 연동(integrations.id) 소속인지 담는 범위.
 *
 * <p>연동을 해제해도 이미 돌던 수집은 멈추지 않고 이벤트를 발행하고 커서를 써서, 해제 때 지운 커서를
 * 되살리고 지운 그래프를 다시 채운다. 재연결하면 새 행·새 id가 생기므로 <b>실행 시작 때의 id</b>를
 * 끝까지 들고 다니며, 발행·커서 쓰기 직전에 그 행이 아직 있는지 확인한다.</p>
 *
 * <p>ThreadLocal인 이유: 발행·커서 쓰기는 항상 collect를 부른 스레드에서 일어나므로, 이렇게 하면
 * 수집기 9곳과 {@link SourceCollector} SPI의 시그니처를 바꾸지 않고도 모든 발행·쓰기 지점이 같은
 * 가드를 탄다. 풀 스레드는 재사용되므로 {@link Scope#close()}가 반드시 이전 상태로 되돌려야 한다.</p>
 */
@Component
public class CollectionRunContext {

    private static final Scope NO_SCOPE = () -> { };

    private final ProjectIntegrationRepository repository;
    private final ThreadLocal<String> current = new ThreadLocal<>();

    public CollectionRunContext(ProjectIntegrationRepository repository) {
        this.repository = repository;
    }

    // integrationId가 없으면(raw 호출·id 없는 요청) 범위를 열지 않아 기존 동작 그대로 간다.
    public Scope open(String integrationId) {
        if (integrationId == null) {
            return NO_SCOPE;
        }
        String previous = current.get();
        current.set(integrationId);
        return () -> {
            if (previous == null) {
                current.remove();
            } else {
                current.set(previous);
            }
        };
    }

    public Optional<String> currentIntegrationId() {
        return Optional.ofNullable(current.get());
    }

    // 범위가 없으면 DB를 부르지 않는다 — 발행·커서 쓰기마다 불리므로 범위 밖 호출에 비용을 얹지 않기 위해서다.
    public void ensureActive() {
        String integrationId = current.get();
        if (integrationId == null) {
            return;
        }
        if (!repository.existsById(UUID.fromString(integrationId))) {
            throw new IntegrationDisconnectedException("연동이 해제됨: integrationId=" + integrationId);
        }
    }

    public interface Scope extends AutoCloseable {
        // AutoCloseable.close()의 checked Exception 선언을 걷어 try-with-resources에서 catch를 강요하지 않는다.
        @Override
        void close();
    }
}
