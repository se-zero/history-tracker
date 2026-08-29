package com.history.pipeline_worker.collection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProjectFairGate: Discord·Google Chat처럼 앱 전체가 자격증명/쿼터를 공유하는 소스에서, 프로젝트별
 * round-robin으로 순번을 배분하는 전역 공정 게이트. 큰 길드/스페이스를 붙인 프로젝트 하나가 공유 자원을
 * 독점하지 못하게 한다.
 */
class ProjectFairGateTest {

    // acquire()가 락을 푼 뒤 타임스탬프를 찍는 사이 currentTimeMillis 절삭·스케줄 지연으로
    // 관측 간격이 1ms 짧아질 수 있다. CI에서 29ms < 30ms 로 깨진 이력이 있다.
    private static final long TIMING_SLACK_MS = 2;

    @Test
    @DisplayName("경합이 없으면 같은 프로젝트의 연속 호출은 매번 minIntervalMs 이상 간격을 둔다 — 예전 고정 딜레이와 동일하게 동작")
    void acquire_singleProject_pacesConsecutiveCallsAtMinInterval() {
        long minIntervalMs = 30;
        ProjectFairGate gate = new ProjectFairGate(minIntervalMs);

        gate.acquire("solo");
        long previous = System.currentTimeMillis();
        for (int i = 0; i < 4; i++) {
            gate.acquire("solo");
            long now = System.currentTimeMillis();
            assertThat(now - previous)
                    .as("solo 프로젝트 연속 acquire 간격 #%d", i)
                    .isGreaterThanOrEqualTo(minIntervalMs - TIMING_SLACK_MS);
            previous = now;
        }
    }

    @Test
    @DisplayName("minIntervalMs=0이면 사실상 즉시 통과한다 — 테스트에서 페이싱을 끄는 용도")
    void acquire_zeroMinInterval_doesNotBlock() {
        ProjectFairGate gate = new ProjectFairGate(0);

        long start = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            gate.acquire("solo");
        }
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(50);
    }

    @Test
    @DisplayName("A가 먼저 돌다가 B가 합류하면, 그 이후 구간은 A·B가 번갈아 슬롯을 받는다 — 순수 FIFO가 아니다")
    void acquire_roundRobin_alternatesBetweenProjectsOnceBothAreContending() throws Exception {
        long minIntervalMs = 30;
        ProjectFairGate gate = new ProjectFairGate(minIntervalMs);

        List<Grant> grants = runRoundRobinScenario(gate, 5, 4);

        int firstBIndex = indexOfFirst(grants, "B");
        assertThat(firstBIndex).as("B가 실제로 합류해 슬롯을 받았는지").isGreaterThanOrEqualTo(0);

        List<Grant> contested = grants.subList(firstBIndex, grants.size());
        for (int i = 0; i < contested.size() - 1; i++) {
            assertThat(contested.get(i + 1).projectId())
                    .as("B 합류 이후 grant #%d -> #%d 는 같은 프로젝트가 연속으로 나오면 안 된다: %s",
                            i, i + 1, contested)
                    .isNotEqualTo(contested.get(i).projectId());
        }
    }

    @Test
    @DisplayName("서로 다른 프로젝트 사이에도 grant 시각 간격은 전역으로 minIntervalMs 이상이다")
    void acquire_globalMinInterval_isRespectedAcrossProjects() throws Exception {
        long minIntervalMs = 30;
        ProjectFairGate gate = new ProjectFairGate(minIntervalMs);

        List<Grant> grants = runRoundRobinScenario(gate, 5, 4);

        for (int i = 0; i < grants.size() - 1; i++) {
            long delta = grants.get(i + 1).timestamp() - grants.get(i).timestamp();
            assertThat(delta)
                    .as("grant #%d(%s) -> #%d(%s) 전역 간격", i, grants.get(i).projectId(),
                            i + 1, grants.get(i + 1).projectId())
                    .isGreaterThanOrEqualTo(minIntervalMs - TIMING_SLACK_MS);
        }
    }

    @Test
    @DisplayName("더 대기할 요청이 없는 프로젝트는 회전에서 빠진다 — 유령으로 남아 다른 프로젝트를 기다리게 하지 않는다")
    void acquire_projectWithNoMoreRequests_dropsFromRotationSoOthersAreNotDelayed() throws Exception {
        long minIntervalMs = 30;
        ProjectFairGate gate = new ProjectFairGate(minIntervalMs);

        for (int i = 0; i < 3; i++) {
            gate.acquire("A");
        }
        // A는 이후로 다시 acquire를 부르지 않는다 — 여기서 회전에서 빠져야 한다

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            long start = System.currentTimeMillis();
            Future<Long> future = pool.submit(() -> {
                gate.acquire("B");
                return System.currentTimeMillis();
            });

            long grantedAt = future.get(2, TimeUnit.SECONDS);
            long elapsed = grantedAt - start;

            assertThat(elapsed)
                    .as("호출을 멈춘 A를 기다리며 B가 지연되면 안 된다")
                    .isLessThan(minIntervalMs * 5);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("같은 프로젝트가 두 스레드에서 동시에 acquire해도 둘 다 빠져나온다 — 한 쪽이 rotation에 못 들어가 영구 대기하지 않는다")
    void acquire_sameProjectConcurrentWaiters_bothComplete() throws Exception {
        long minIntervalMs = 200;
        ProjectFairGate gate = new ProjectFairGate(minIntervalMs);
        // 첫 슬롯을 소비해 다음 acquire가 대기 구간에 들어가게 한다
        gate.acquire("same");

        Thread first = new Thread(() -> gate.acquire("same"), "fair-gate-same-1");
        Thread second = new Thread(() -> gate.acquire("same"), "fair-gate-same-2");
        try {
            first.start();
            // first가 waiting에 들어간 뒤에 second가 합류해야 영구 대기 버그가 재현된다
            Thread.sleep(50);
            second.start();

            first.join(3_000);
            second.join(3_000);

            assertThat(first.isAlive())
                    .as("first waiter가 시간 안에 acquire를 빠져나와야 한다")
                    .isFalse();
            assertThat(second.isAlive())
                    .as("second waiter가 rotation에 재진입하지 못해 멈추면 안 된다")
                    .isFalse();
        } finally {
            first.interrupt();
            second.interrupt();
        }
    }

    /**
     * A 스레드가 반복 acquire하다가(총 {@code aTotalIterations}회) 그 첫 acquire가 끝난 직후 B 스레드가
     * 합류해 반복 acquire한다(총 {@code bIterations}회). 이후 두 스레드는 동시에 계속 경합한다.
     */
    private List<Grant> runRoundRobinScenario(ProjectFairGate gate, int aTotalIterations, int bIterations)
            throws InterruptedException {
        List<Grant> grants = new CopyOnWriteArrayList<>();
        CountDownLatch aFirstAcquired = new CountDownLatch(1);

        Thread threadA = new Thread(() -> {
            for (int i = 0; i < aTotalIterations; i++) {
                gate.acquire("A");
                grants.add(new Grant("A", System.currentTimeMillis()));
                if (i == 0) {
                    aFirstAcquired.countDown();
                }
            }
        });
        Thread threadB = new Thread(() -> {
            awaitQuietly(aFirstAcquired);
            for (int i = 0; i < bIterations; i++) {
                gate.acquire("B");
                grants.add(new Grant("B", System.currentTimeMillis()));
            }
        });

        threadA.start();
        threadB.start();
        threadA.join(5000);
        threadB.join(5000);
        return grants;
    }

    private static int indexOfFirst(List<Grant> grants, String projectId) {
        for (int i = 0; i < grants.size(); i++) {
            if (grants.get(i).projectId().equals(projectId)) {
                return i;
            }
        }
        return -1;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Grant(String projectId, long timestamp) {}
}
