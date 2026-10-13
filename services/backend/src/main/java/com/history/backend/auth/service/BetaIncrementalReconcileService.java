package com.history.backend.auth.service;

import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// FREE 사용자 연동의 증분 수집 플래그를 "베타 중 && 미탈퇴"에 맞추는 정리 작업.
// @Transactional을 붙이지 않는다 — 붙이면 PlanService.reconcileFreeIncremental이 이 메서드의 바깥
// 트랜잭션에 합류해, 한 건 실패가 전체를 rollback-only로 만들고 이미 정리된 사용자까지 되돌린다
// (PlanExpiryService와 같은 이유).
@Slf4j
@Service
@RequiredArgsConstructor
public class BetaIncrementalReconcileService {

    private final PlanService planService;

    public int reconcile() {
        List<UUID> candidateIds = planService.findIncrementalReconcileCandidates();
        int reconciledCount = 0;
        for (UUID userId : candidateIds) {
            try {
                // 재확인에서 건너뛴 계정(false)까지 세면 로그 count가 실제 정리 건수보다 커진다.
                if (planService.reconcileFreeIncremental(userId)) {
                    reconciledCount++;
                }
            } catch (RuntimeException exception) {
                log.warn("Failed to reconcile incremental flag for user. userId={}", userId, exception);
            }
        }
        if (reconciledCount > 0) {
            log.info("Reconciled incremental flags of free users. count={}", reconciledCount);
        }
        return reconciledCount;
    }
}
