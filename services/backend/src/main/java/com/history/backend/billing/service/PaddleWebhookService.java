package com.history.backend.billing.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.auth.service.PlanService;
import com.history.backend.billing.BillingProperties;
import com.history.backend.billing.PaddleProperties;
import com.history.backend.billing.domain.BillingEventOutcome;
import com.history.backend.billing.domain.BillingSubscription;
import com.history.backend.billing.repository.BillingEventRepository;
import com.history.backend.billing.repository.BillingSubscriptionRepository;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.common.error.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Paddle 결제 알림(웹훅) 수신 — 서명 검증 → 파싱 → 멱등 claim → 구독 상태 동기화 → 플랜 반영.
// Paddle이 진실의 원천이고 우리 DB는 캐시라, 알림이 중복·역순으로 와도 최신 상태로 수렴하도록 짠다.
@Slf4j
@Service
@Transactional
public class PaddleWebhookService {

    // D1: 연체(past_due) 동안 Pro 유지 — Paddle 권장. 재시도가 끝내 실패하면 Paddle이 canceled를 보낸다.
    private static final Set<String> ACTIVE_LIKE_STATUSES = Set.of("active", "trialing", "past_due");
    private static final Set<String> INACTIVE_LIKE_STATUSES = Set.of("canceled", "paused");

    private final PaddleSignatureVerifier verifier;
    private final BillingEventRepository billingEventRepository;
    private final BillingSubscriptionRepository billingSubscriptionRepository;
    private final PlanService planService;
    private final PaddleProperties paddleProperties;
    private final BillingProperties billingProperties;
    private final ObjectMapper objectMapper;

    // Spring 빈 등록 생성자 — 이 프로젝트는 ObjectMapper를 Spring 빈으로 등록하지 않아 직접 생성한다
    @Autowired
    public PaddleWebhookService(
            PaddleSignatureVerifier verifier,
            BillingEventRepository billingEventRepository,
            BillingSubscriptionRepository billingSubscriptionRepository,
            PlanService planService,
            PaddleProperties paddleProperties,
            BillingProperties billingProperties
    ) {
        this(verifier, billingEventRepository, billingSubscriptionRepository, planService,
                paddleProperties, billingProperties, new ObjectMapper());
    }

    // 테스트 전용 생성자 — 실제 ObjectMapper 주입으로 JSON 파싱 동작을 검증한다
    PaddleWebhookService(
            PaddleSignatureVerifier verifier,
            BillingEventRepository billingEventRepository,
            BillingSubscriptionRepository billingSubscriptionRepository,
            PlanService planService,
            PaddleProperties paddleProperties,
            BillingProperties billingProperties,
            ObjectMapper objectMapper
    ) {
        this.verifier = verifier;
        this.billingEventRepository = billingEventRepository;
        this.billingSubscriptionRepository = billingSubscriptionRepository;
        this.planService = planService;
        this.paddleProperties = paddleProperties;
        this.billingProperties = billingProperties;
        this.objectMapper = objectMapper;
    }

    public void handle(String signatureHeader, String rawBody) {
        if (!verifier.verify(signatureHeader, rawBody)) {
            throw new UnauthorizedException("Invalid Paddle webhook signature.");
        }

        JsonNode envelope = parseJson(rawBody);
        String eventId = textOrNull(envelope, "event_id");
        String eventType = textOrNull(envelope, "event_type");
        String occurredAtRaw = textOrNull(envelope, "occurred_at");
        String notificationId = textOrNull(envelope, "notification_id");
        if (eventId == null || eventType == null || occurredAtRaw == null) {
            throw new BadRequestException("Missing required Paddle webhook envelope field.");
        }
        Instant occurredAt = parseInstant(occurredAtRaw);

        // 원장 선점 — 0행이면 이미 처리했거나 처리 중인 알림(재시도 포함)이므로 더 볼 것 없이 끝낸다
        int claimed = billingEventRepository.tryClaim(eventId, eventType, occurredAt, notificationId, Instant.now());
        if (claimed == 0) {
            log.info("Paddle webhook: 중복 알림, 무시. eventId={}, eventType={}", eventId, eventType);
            return;
        }

        if (!eventType.startsWith("subscription.")) {
            recordOutcome(eventId, eventType, BillingEventOutcome.IGNORED, null, null);
            return;
        }

        JsonNode data = envelope.path("data");
        String subscriptionId = textOrNull(data, "id");
        String status = textOrNull(data, "status");
        String customerId = textOrNull(data, "customer_id");

        // data.id가 없으면 findById(null)이 IllegalArgumentException을 던진다(500 → 무한 재시도) —
        // 가격 검사·사용자 조회보다 먼저 걸러 subscriptionId·userId 둘 다 null로 IGNORED 기록한다.
        if (subscriptionId == null) {
            log.warn("Paddle webhook: subscription 이벤트에 data.id가 없음. eventId={}, eventType={}", eventId, eventType);
            recordOutcome(eventId, eventType, BillingEventOutcome.IGNORED, null, null);
            return;
        }

        // customer_id는 billing_subscriptions에서 NOT NULL이다 — 없는 채로 save하면 제약 위반으로
        // 500 → Paddle이 최대 3일간 재시도하게 된다. data.id 가드와 같은 급으로 가격 검사보다 먼저 걸러낸다.
        if (customerId == null) {
            log.warn("Paddle webhook: subscription 이벤트에 customer_id가 없음. eventId={}, eventType={}", eventId, eventType);
            recordOutcome(eventId, eventType, BillingEventOutcome.IGNORED, subscriptionId, null);
            return;
        }

        JsonNode items = data.path("items");
        boolean hasItems = items.isArray() && !items.isEmpty();
        String proPriceId = paddleProperties.proPriceId();
        boolean proPriceConfigured = proPriceId != null && !proPriceId.isBlank();

        // 가격 검사가 사용자 찾기보다 먼저 — pro-price-id가 설정돼 있고 일치하는 항목이 없으면
        // 사용자 조회조차 하지 않는다. 캐시 조회(findById)는 stale 비교·user 폴백과 공용으로 1회만
        // 하므로, items가 없어 캐시의 priceId로 대신 검사해야 하는 경우에는 그 조회를 여기로 당겨온다.
        Optional<BillingSubscription> existing;
        String priceId;
        if (hasItems) {
            if (proPriceConfigured) {
                priceId = findMatchingItemPriceId(items, proPriceId);
                if (priceId == null) {
                    recordOutcome(eventId, eventType, BillingEventOutcome.IGNORED, subscriptionId, null);
                    return;
                }
            } else {
                priceId = firstItemPriceId(data);
            }
            existing = billingSubscriptionRepository.findById(subscriptionId);
        } else {
            existing = billingSubscriptionRepository.findById(subscriptionId);
            priceId = existing.map(BillingSubscription::getPriceId).orElse(null);
            if (proPriceConfigured && !proPriceId.equals(priceId)) {
                recordOutcome(eventId, eventType, BillingEventOutcome.IGNORED, subscriptionId, null);
                return;
            }
        }

        UUID userId = resolveUserId(data, existing);
        if (userId == null) {
            log.warn("Paddle webhook: user_id를 찾을 수 없음. subscriptionId={}", subscriptionId);
            recordOutcome(eventId, eventType, BillingEventOutcome.UNMATCHED, subscriptionId, null);
            return;
        }

        // 순서 역전 방어 — 저장된 최신 알림보다 오래된 알림이면 무시(재시도 때 subscription.updated가
        // subscription.created보다 먼저 오는 등 Paddle은 순서를 보장하지 않는다)
        if (existing.isPresent() && existing.get().getLastEventOccurredAt().isAfter(occurredAt)) {
            recordOutcome(eventId, eventType, BillingEventOutcome.STALE, subscriptionId, userId);
            return;
        }

        String scheduledChangeAction = null;
        Instant scheduledChangeEffectiveAt = null;
        JsonNode scheduledChange = data.path("scheduled_change");
        if (scheduledChange.isObject()) {
            scheduledChangeAction = textOrNull(scheduledChange, "action");
            String effectiveAtRaw = textOrNull(scheduledChange, "effective_at");
            scheduledChangeEffectiveAt = effectiveAtRaw == null ? null : parseInstant(effectiveAtRaw);
        }
        String canceledAtRaw = textOrNull(data, "canceled_at");
        Instant canceledAt = canceledAtRaw == null ? null : parseInstant(canceledAtRaw);

        BillingEventOutcome outcome;
        try {
            if (ACTIVE_LIKE_STATUSES.contains(status)) {
                Instant expiresAt = resolveExpiry(data);
                if (expiresAt == null) {
                    log.warn("Paddle webhook: 만료 시각을 계산할 수 없어 무시. subscriptionId={}", subscriptionId);
                    recordOutcome(eventId, eventType, BillingEventOutcome.IGNORED, subscriptionId, userId);
                    return;
                }
                planService.activatePaid(userId, expiresAt);
            } else if (INACTIVE_LIKE_STATUSES.contains(status)) {
                boolean hasOtherLiveSubscription = billingSubscriptionRepository
                        .existsByUserIdAndStatusInAndSubscriptionIdNot(
                                userId, List.copyOf(ACTIVE_LIKE_STATUSES), subscriptionId);
                if (!hasOtherLiveSubscription) {
                    planService.downgradeToFree(userId);
                }
            } else {
                // Paddle이 새 상태 값을 추가하는 경우 — 이 분기는 PlanService를 거치지 않아 사용자 없음
                // 보호(noRollbackFor UNMATCHED 처리)가 없다. 파기된 사용자면 캐시 upsert가 user_id FK
                // 위반으로 500·무한 재시도가 되므로, 플랜뿐 아니라 캐시도 건드리지 않고 IGNORED로만 남긴다.
                log.warn("Paddle webhook: 알 수 없는 구독 상태, 플랜·캐시 변경 없이 무시. status={}", status);
                recordOutcome(eventId, eventType, BillingEventOutcome.IGNORED, subscriptionId, userId);
                return;
            }
            billingSubscriptionRepository.save(new BillingSubscription(
                    subscriptionId, userId, customerId, status, priceId,
                    resolveCurrentPeriodEndsAt(data), scheduledChangeAction, scheduledChangeEffectiveAt,
                    canceledAt, occurredAt
            ));
            outcome = BillingEventOutcome.APPLIED;
        } catch (NotFoundException e) {
            // 사용자가 파기된 뒤에도 알림은 계속 올 수 있다 — 예외를 전파하지 않고 UNMATCHED로 기록한다
            outcome = BillingEventOutcome.UNMATCHED;
        }

        recordOutcome(eventId, eventType, outcome, subscriptionId, userId);
    }

    private JsonNode parseJson(String rawBody) {
        try {
            return objectMapper.readTree(rawBody);
        } catch (Exception e) {
            throw new BadRequestException("Malformed Paddle webhook payload.");
        }
    }

    private Instant parseInstant(String raw) {
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Invalid Paddle webhook timestamp: " + raw);
        }
    }

    // 필드가 없거나(MissingNode) JSON null이면 null을 돌려준다 — Jackson의 NullNode.asText(default)는
    // default를 무시하고 문자열 "null"을 돌려주므로 isNull()을 먼저 확인해야 한다.
    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private String firstItemPriceId(JsonNode data) {
        JsonNode items = data.path("items");
        if (!items.isArray() || items.isEmpty()) {
            return null;
        }
        return textOrNull(items.get(0).path("price"), "id");
    }

    // proPriceId가 설정된 경우 items 중 하나라도 일치하는 항목을 찾는다 — items[0]만 보면 Pro
    // 가격이 두 번째 이후에 올 때(예: 애드온과 함께 결제) 놓친다.
    private String findMatchingItemPriceId(JsonNode items, String proPriceId) {
        for (JsonNode item : items) {
            String candidate = textOrNull(item.path("price"), "id");
            if (proPriceId.equals(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // custom_data.user_id 우선, 없으면 같은 subscription_id의 기존 구독 캐시로 폴백.
    // Paddle은 결제의 custom_data를 구독과 갱신 결제에 복사하므로 보통은 첫 경로로 찾는다 — 폴백은
    // 체크아웃이 custom_data를 싣지 않았거나 운영자가 대시보드에서 구독을 만든 경우를 위한 것이다.
    private UUID resolveUserId(JsonNode data, Optional<BillingSubscription> existing) {
        JsonNode customData = data.path("custom_data");
        if (customData.isObject()) {
            String userIdRaw = textOrNull(customData, "user_id");
            if (userIdRaw != null) {
                try {
                    return UUID.fromString(userIdRaw);
                } catch (IllegalArgumentException e) {
                    log.warn("Paddle webhook: custom_data.user_id가 UUID 형식이 아님.");
                }
            }
        }
        return existing.map(BillingSubscription::getUserId).orElse(null);
    }

    private Instant resolveCurrentPeriodEndsAt(JsonNode data) {
        JsonNode period = data.path("current_billing_period");
        if (!period.isObject()) {
            return null;
        }
        String endsAt = textOrNull(period, "ends_at");
        return endsAt == null ? null : parseInstant(endsAt);
    }

    // current_billing_period.ends_at을 우선하고, 해지 후처럼 null이면 next_billed_at으로 대체한다.
    // 둘 다 없으면 만료를 계산할 수 없다(호출부가 IGNORED로 처리).
    private Instant resolveExpiry(JsonNode data) {
        Instant candidate = resolveCurrentPeriodEndsAt(data);
        if (candidate == null) {
            String nextBilledAt = textOrNull(data, "next_billed_at");
            candidate = nextBilledAt == null ? null : parseInstant(nextBilledAt);
        }
        return candidate == null ? null : candidate.plus(billingProperties.expiryGrace());
    }

    private void recordOutcome(
            String eventId, String eventType, BillingEventOutcome outcome, String subscriptionId, UUID userId
    ) {
        billingEventRepository.updateOutcome(eventId, outcome.name(), subscriptionId, userId);
        // 본문·custom_data·이메일은 남기지 않는다 — "왜 플랜이 안 바뀌었나"를 outcome만으로 추적한다
        log.info("Paddle webhook 처리 완료. eventId={}, eventType={}, subscriptionId={}, userId={}, outcome={}",
                eventId, eventType, subscriptionId, userId, outcome);
    }
}
