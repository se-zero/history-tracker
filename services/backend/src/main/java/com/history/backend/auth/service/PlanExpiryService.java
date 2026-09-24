package com.history.backend.auth.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.history.backend.auth.PlanExpiryProperties;
import com.history.backend.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

// 만료된 PAID 플랜을 FREE로 강등하는 결제 웹훅 유실 안전망.
// @Transactional을 붙이지 않는다 — 붙이면 PlanService.downgradeIfExpired(클래스 레벨 @Transactional로
// 건별 독립 트랜잭션)가 이 메서드의 바깥 트랜잭션에 합류해, 한 건 실패가 트랜잭션 전체를
// rollback-only로 만들고 이미 성공한 강등까지 UnexpectedRollbackException으로 전부 되돌린다.
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanExpiryService {

    private final UserRepository userRepository;
    private final PlanService planService;
    private final PlanExpiryProperties properties;

    public int downgradeExpiredPlans() {
        return downgradeExpiredPlans(Instant.now());
    }

    // 한 번 실행에 batchSize건만 처리한다 — 성공한 건은 FREE가 되어 다음 조회에서 빠지므로
    // 나머지는 다음 스케줄러 실행이 가져간다.
    public int downgradeExpiredPlans(Instant now) {
        List<UUID> candidateIds =
                userRepository.findExpiredPaidUserIds(now, PageRequest.of(0, properties.batchSize()));
        int downgradedCount = 0;
        for (UUID userId : candidateIds) {
            try {
                // 재확인에서 건너뛴 계정(false)까지 세면 로그 count가 실제 강등 건수보다 커진다.
                if (planService.downgradeIfExpired(userId, now)) {
                    downgradedCount++;
                }
            } catch (RuntimeException exception) {
                // 드물고 원인 파악이 중요한 실패라 메시지만이 아니라 스택 트레이스까지 남긴다
                log.warn("Failed to downgrade expired paid plan for user. userId={}", userId, exception);
            }
        }
        return downgradedCount;
    }
}
