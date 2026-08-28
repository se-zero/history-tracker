package com.history.pipeline_worker.collection;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

// 여러 프로젝트가 provider 자격증명 하나(Discord 봇 토큰, Google Chat Cloud 프로젝트)를 공유할 때,
// 한 프로젝트가 연속 호출로 그 자원을 독점하지 못하게 라운드로빈으로 순번을 배정한다. 한 프로젝트가
// 여러 스레드에서 동시에 acquire하는 경우는 다루지 않는다 — 이 provider들의 수집은 프로젝트당 항상
// 순차 실행이므로 동시 대기가 없다는 게 전제다.
public class ProjectFairGate {

    private final long minIntervalMs;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    private final Deque<String> rotation = new ArrayDeque<>();
    private final Set<String> waiting = new HashSet<>();
    private long nextSlotAt = 0L;

    public ProjectFairGate(long minIntervalMs) {
        this.minIntervalMs = minIntervalMs;
    }

    public void acquire(String projectId) {
        lock.lock();
        try {
            if (waiting.add(projectId)) {
                rotation.addLast(projectId);
            }
            while (true) {
                long now = System.currentTimeMillis();
                boolean myTurn = projectId.equals(rotation.peekFirst());
                if (myTurn && now >= nextSlotAt) {
                    rotation.pollFirst();
                    waiting.remove(projectId);
                    nextSlotAt = now + minIntervalMs;
                    condition.signalAll();
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
            lock.unlock();
        }
    }
}
