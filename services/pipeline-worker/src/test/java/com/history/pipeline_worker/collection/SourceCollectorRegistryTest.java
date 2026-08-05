package com.history.pipeline_worker.collection;

import com.history.pipeline_worker.dto.RawFetchRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceCollectorRegistryTest {

    @Test
    void get_returnsCollectorRegisteredForProvider() {
        SourceCollector github = new StubCollector(CollectionProvider.GITHUB);
        SourceCollector slack = new StubCollector(CollectionProvider.SLACK);
        SourceCollectorRegistry registry = new SourceCollectorRegistry(List.of(github, slack));

        assertThat(registry.get(CollectionProvider.GITHUB)).isSameAs(github);
        assertThat(registry.find(CollectionProvider.SLACK)).contains(slack);
    }

    @Test
    void find_unregisteredProvider_isEmpty() {
        SourceCollectorRegistry registry = new SourceCollectorRegistry(List.of(new StubCollector(CollectionProvider.GITHUB)));

        assertThat(registry.find(CollectionProvider.JIRA)).isEmpty();
        assertThatThrownBy(() -> registry.get(CollectionProvider.JIRA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No SourceCollector registered");
    }

    @Test
    void duplicateProvider_failsFastAtStartup() {
        // 같은 provider를 두 빈이 주장하면 어느 쪽이 수집을 담당하는지 불확실해진다 — 조용히 덮지 않는다.
        assertThatThrownBy(() -> new SourceCollectorRegistry(List.of(
                new StubCollector(CollectionProvider.JIRA),
                new StubCollector(CollectionProvider.JIRA)
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate SourceCollector");
    }

    private record StubCollector(CollectionProvider provider) implements SourceCollector {

        @Override
        public Optional<RawFetchRequest> resolveFetchRequest(ProjectIntegrationRepository.IntegrationRow integration) {
            return Optional.empty();
        }

        @Override
        public int collect(String projectId, RawFetchRequest request) {
            return 0;
        }
    }
}
