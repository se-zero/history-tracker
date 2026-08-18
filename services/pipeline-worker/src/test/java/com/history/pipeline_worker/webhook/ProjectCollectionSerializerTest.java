package com.history.pipeline_worker.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProjectCollectionSerializer: 같은 projectId는 직렬, 다른 projectId는 병렬.
 * "a"(hash 97)와 "b"(hash 98)는 stripe 64에서 서로 다른 stripe로 매핑돼 병렬 검증이 결정론적이다.
 */
class ProjectCollectionSerializerTest {

    @Test
    @DisplayName("같은 projectId 호출은 직렬화되어 임계 구역이 겹치지 않는다")
    void sameProject_serializes() throws Exception {
        ProjectCollectionSerializer serializer = new ProjectCollectionSerializer(64);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(2);

        Runnable task = () -> serializer.callExclusively("a", () -> {
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            sleep(50);
            concurrent.decrementAndGet();
            done.countDown();
            return null;
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.execute(task);
        pool.execute(task);

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(maxConcurrent.get()).as("동시 진입 최대치").isEqualTo(1);
        pool.shutdownNow();
    }

    @Test
    @DisplayName("다른 projectId 호출은 동시에 임계 구역에 진입할 수 있다")
    void differentProjects_runConcurrently() throws Exception {
        ProjectCollectionSerializer serializer = new ProjectCollectionSerializer(64);
        CountDownLatch bothInside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        Runnable taskA = () -> serializer.callExclusively("a", () -> {
            bothInside.countDown();
            awaitQuietly(release);
            return null;
        });
        Runnable taskB = () -> serializer.callExclusively("b", () -> {
            bothInside.countDown();
            awaitQuietly(release);
            return null;
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.execute(taskA);
        pool.execute(taskB);

        // 둘 다 임계 구역에 들어왔다면 서로 다른 lock(stripe) → 병렬
        assertThat(bothInside.await(2, TimeUnit.SECONDS))
                .as("두 프로젝트가 동시에 임계 구역 진입").isTrue();
        release.countDown();
        pool.shutdownNow();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
