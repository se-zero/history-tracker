package com.history.backend.billing.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.billing.domain.BillingEventOutcome;
import com.history.backend.billing.repository.BillingEventRepository;
import com.history.backend.billing.service.PaddleSubscriptionApplier.ApplyOutcome;
import com.history.backend.billing.service.PaddleSubscriptionApplier.ApplyResult;
import com.history.backend.billing.service.PaddleSubscriptionApplier.ApplySource;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Paddle 결제 알림(웹훅) 수신 — 서명 검증 → 봉투 파싱 → 멱등 claim → 구독 상태 반영(PaddleSubscriptionApplier).
// 승인된 환불(adjustment)은 구독 상태를 바꾸지 않으므로, 구독 알림과 달리 구독을 직접 해지해(SubscriptionRefundService) 반영한다.
// Paddle이 진실의 원천이고 우리 DB는 캐시라, 알림이 중복·역순으로 와도 최신 상태로 수렴하도록 짠다.
// 클래스 단위 트랜잭션이라 claim·반영·outcome 기록이 한 번에 커밋되고, 예외가 나면 claim까지 롤백돼 Paddle이 재시도한다.
@Slf4j
@Service
@Transactional
public class PaddleWebhookService {

    private final PaddleSignatureVerifier verifier;
    private final BillingEventRepository billingEventRepository;
    private final PaddleSubscriptionParser subscriptionParser;
    private final PaddleSubscriptionApplier subscriptionApplier;
    private final SubscriptionRefundService refundService;
    private final ObjectMapper objectMapper;

    // Spring 빈 등록 생성자 — 이 프로젝트는 ObjectMapper를 Spring 빈으로 등록하지 않아 직접 생성한다
    @Autowired
    public PaddleWebhookService(
            PaddleSignatureVerifier verifier,
            BillingEventRepository billingEventRepository,
            PaddleSubscriptionParser subscriptionParser,
            PaddleSubscriptionApplier subscriptionApplier,
            SubscriptionRefundService refundService
    ) {
        this(verifier, billingEventRepository, subscriptionParser, subscriptionApplier, refundService,
                new ObjectMapper());
    }

    // 테스트 전용 생성자 — 실제 ObjectMapper 주입으로 JSON 파싱 동작을 검증한다
    PaddleWebhookService(
            PaddleSignatureVerifier verifier,
            BillingEventRepository billingEventRepository,
            PaddleSubscriptionParser subscriptionParser,
            PaddleSubscriptionApplier subscriptionApplier,
            SubscriptionRefundService refundService,
            ObjectMapper objectMapper
    ) {
        this.verifier = verifier;
        this.billingEventRepository = billingEventRepository;
        this.subscriptionParser = subscriptionParser;
        this.subscriptionApplier = subscriptionApplier;
        this.refundService = refundService;
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

        if (eventType.equals("adjustment.created") || eventType.equals("adjustment.updated")) {
            handleAdjustment(eventId, eventType, envelope.path("data"));
            return;
        }

        if (!eventType.startsWith("subscription.")) {
            recordOutcome(eventId, eventType, BillingEventOutcome.IGNORED, null, null);
            return;
        }

        PaddleSubscriptionState state;
        try {
            state = subscriptionParser.parse(envelope.path("data"));
        } catch (PaddlePayloadException e) {
            throw new BadRequestException(e.getMessage());
        }

        // 봉투의 occurred_at은 구독 updated_at이 없을 때만 순서 기준으로 쓰인다
        ApplyResult result = subscriptionApplier.apply(state, occurredAt, ApplySource.WEBHOOK);
        recordOutcome(eventId, eventType, toBillingEventOutcome(result.outcome()),
                result.subscriptionId(), result.userId());
    }

    // 자동 승인 환불은 created로 승인 상태가 오고, 검토를 거치는 환불은 created(pending_approval) 뒤 updated(approved)가 온다.
    // 환불 type(full·partial)은 보지 않는다 — 자동 결제 구독에는 해지 없이 돈만 돌려줄 수단이 없어 부분 환불도 해지한다.
    // 해지 실패는 삼키지 않는다: 예외가 나면 claim까지 롤백돼 Paddle이 같은 알림을 다시 보낸다.
    private void handleAdjustment(String eventId, String eventType, JsonNode data) {
        String subscriptionId = textOrNull(data, "subscription_id");
        if (!"refund".equals(textOrNull(data, "action"))
                || !"approved".equals(textOrNull(data, "status"))
                || subscriptionId == null) {
            recordOutcome(eventId, eventType, BillingEventOutcome.IGNORED, null, null);
            return;
        }

        ApplyResult result = refundService.cancelForApprovedRefund(subscriptionId, textOrNull(data, "customer_id"));
        // UNCHANGED는 해지 상태가 이미 반영돼 있다는 뜻이라 처리가 끝난 것이다
        BillingEventOutcome outcome = result.outcome() == ApplyOutcome.UNCHANGED
                ? BillingEventOutcome.APPLIED
                : toBillingEventOutcome(result.outcome());
        recordOutcome(eventId, eventType, outcome, result.subscriptionId(), result.userId());
    }

    // UNCHANGED는 API 출처에서만 나온다(웹훅은 같은 시각도 반영) — 만에 하나 오면 반영하지 않은 것이므로 STALE로 둔다
    private BillingEventOutcome toBillingEventOutcome(ApplyOutcome outcome) {
        return switch (outcome) {
            case APPLIED -> BillingEventOutcome.APPLIED;
            case STALE, UNCHANGED -> BillingEventOutcome.STALE;
            case IGNORED -> BillingEventOutcome.IGNORED;
            case UNMATCHED -> BillingEventOutcome.UNMATCHED;
        };
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
            // 원문은 응답에 싣지 않는다 — 파서(PaddleSubscriptionParser)의 시각 오류와 같은 규칙
            throw new BadRequestException("Invalid Paddle webhook timestamp.");
        }
    }

    // 필드가 없거나(MissingNode) JSON null이면 null을 돌려준다 — Jackson의 NullNode.asText(default)는
    // default를 무시하고 문자열 "null"을 돌려주므로 isNull()을 먼저 확인해야 한다.
    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
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
