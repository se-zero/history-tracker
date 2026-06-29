package com.history.pipeline_worker.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 같은 프로젝트의 webhook 수집(collectIncremental)을 직렬화한다.
 *
 * webhook 풀 동시성을 1보다 키우면(P-2 2단계), 동일 프로젝트의 서로 다른 delivery 2건이
 * collectIncremental을 동시에 돌 수 있다. 둘은 같은 checkpoint를 읽고 같은 구간을 중복 스캔해
 * API·rate-limit을 이중 소모한다. (checkpoint upsert가 GREATEST 단조라 유실로 이어지진 않지만 낭비다.)
 *
 * project id 기반 striped lock으로 같은 프로젝트는 직렬, 다른 프로젝트는 병렬로 실행한다.
 * stripe 수만큼만 lock을 두어 프로젝트 수에 무관하게 메모리가 고정된다(드물게 서로 다른 프로젝트가
 * 같은 stripe를 공유해 직렬화될 수 있으나 정확성에는 영향 없음).
 */
@Component
public class ProjectCollectionSerializer {

    private final Lock[] stripes;

    public ProjectCollectionSerializer(
            @Value("${app.webhook.collection-lock-stripes:32}") int stripeCount
    ) {
        this.stripes = new Lock[Math.max(1, stripeCount)];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    /** projectId 단위 lock을 잡고 task를 실행한다. 같은 projectId 호출은 직렬화된다. */
    public <T> T callExclusively(String projectId, Supplier<T> task) {
        Lock lock = stripeFor(projectId);
        lock.lock();
        try {
            return task.get();
        } finally {
            lock.unlock();
        }
    }

    private Lock stripeFor(String projectId) {
        return stripes[Math.floorMod(projectId.hashCode(), stripes.length)];
    }
}
