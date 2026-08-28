package com.history.pipeline_worker.collection;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

// 여러 프로젝트가 provider 자격증명 하나(Discord 봇 토큰, Google Chat Cloud 프로젝트)를 공유할 때,
// 한 프로젝트가 연속 호출로 그 자원을 독점하지 못하게 라운드로빈으로 순번을 배정한다. 같은 프로젝트의
// 초기 수집과 webhook 수집이 겹쳐 waiter가 둘 이상이 되어도, 프로젝트당 대기 수를 세어 슬롯을 다시
// 회전열 뒤로 넣기 때문에 한 쪽이 영구히 멈추지 않는다.
public class ProjectFairGate {

    private final long minIntervalMs;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    private final Deque<String> rotation = new ArrayDeque<>();
    private final Map<String, Integer> waiterCount = new HashMap<>();
    private long nextSlotAt = 0L;

    public ProjectFairGate(long minIntervalMs) {
        this.minIntervalMs = minIntervalMs;
    }

    public void acquire(String projectId) {
        lock.lock();
        boolean enqueued = false;
        try {
            enqueue(projectId);
            enqueued = true;
            while (true) {
                long now = System.currentTimeMillis();
                boolean myTurn = projectId.equals(rotation.peekFirst());
                if (myTurn && now >= nextSlotAt) {
                    grant(projectId);
                    enqueued = false;
                    return;
                }
                long waitMs = myTurn ? Math.max(1, nextSlotAt - now) : minIntervalMs;
                try {
                    condition.await(waitMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("ProjectFairGate acquire 중단", e);
                }
            }
        } finally {
            if (enqueued) {
                dropWaiter(projectId);
            }
            lock.unlock();
        }
    }

    private void enqueue(String projectId) {
        int count = waiterCount.getOrDefault(projectId, 0);
        if (count == 0) {
            rotation.addLast(projectId);
        }
        waiterCount.put(projectId, count + 1);
    }

    private void grant(String projectId) {
        int remaining = waiterCount.get(projectId) - 1;
        rotation.pollFirst();
        if (remaining > 0) {
            waiterCount.put(projectId, remaining);
            rotation.addLast(projectId);
        } else {
            waiterCount.remove(projectId);
        }
        nextSlotAt = System.currentTimeMillis() + minIntervalMs;
        condition.signalAll();
    }

    private void dropWaiter(String projectId) {
        Integer count = waiterCount.get(projectId);
        if (count == null) {
            return;
        }
        if (count <= 1) {
            waiterCount.remove(projectId);
            rotation.remove(projectId);
        } else {
            waiterCount.put(projectId, count - 1);
        }
        condition.signalAll();
    }
}
