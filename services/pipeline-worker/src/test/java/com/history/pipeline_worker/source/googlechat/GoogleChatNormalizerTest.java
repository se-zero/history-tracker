package com.history.pipeline_worker.source.googlechat;

import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.normalizer.RefsExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleChatNormalizerTest {

    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";

    private final GoogleChatNormalizer normalizer = new GoogleChatNormalizer(new RefsExtractor());

    @Test
    @DisplayName("루트 메시지 정규화 — url은 리소스 이름 원문, conversation_id는 자기 자신")
    void normalizeMessages_rootMessage_mapsCoreFields() {
        Map<String, Object> message = Map.of(
                "name", "spaces/AAAA/messages/M1",
                "text", "PLAT-123 관련 배포했습니다",
                "createTime", "2026-08-08T03:00:00Z",
                "sender", Map.of("name", "users/U1", "type", "HUMAN")
        );

        List<NormalizedEvent> events = normalizer.normalizeMessages(PROJECT_ID, "engineering", List.of(message), Map.of());

        assertThat(events).hasSize(1);
        NormalizedEvent event = events.get(0);
        assertThat(event.source()).isEqualTo("GOOGLE_CHAT");
        assertThat(event.nodeType()).isEqualTo("Communication");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-08T03:00:00Z"));
        assertThat(event.properties())
                .containsEntry("url", "spaces/AAAA/messages/M1")
                .containsEntry("body", "PLAT-123 관련 배포했습니다")
                .containsEntry("channel", "engineering")
                .containsEntry("conversation_id", "spaces/AAAA/messages/M1")
                .containsEntry("created_at", "2026-08-08T03:00:00Z");
        assertThat(event.actor().id()).isEqualTo("U1");
        assertThat(event.refs()).containsEntry("issueKey", "PLAT-123");
    }

    @Test
    @DisplayName("actorInfo(People API 보강)에 있으면 이름·이메일을 채운다 — 실제 API 경로")
    void normalizeMessages_actorInfoResolved_fillsNameAndEmail() {
        Map<String, Object> message = Map.of(
                "name", "spaces/AAAA/messages/M1",
                "text", "본문",
                "createTime", "2026-08-08T03:00:00Z",
                // 실측 확인(2026-08-08) — 사용자 인증에서는 sender에 name·type만 오고 displayName은 없다
                "sender", Map.of("name", "users/U1", "type", "HUMAN")
        );
        Map<String, GoogleChatRawService.PersonInfo> actorInfo =
                Map.of("users/U1", new GoogleChatRawService.PersonInfo("Alice", "alice@example.com"));

        NormalizedEvent event = normalizer.normalizeMessages(PROJECT_ID, "engineering", List.of(message), actorInfo).get(0);

        assertThat(event.actor().id()).isEqualTo("U1");
        assertThat(event.actor().name()).isEqualTo("Alice");
        assertThat(event.actor().email()).isEqualTo("alice@example.com");
    }

    @Test
    @DisplayName("actorInfo에 없으면(조회 실패·프로필 비공개 등) 이름·이메일은 null이다")
    void normalizeMessages_actorInfoMissing_leavesNameAndEmailNull() {
        Map<String, Object> message = Map.of(
                "name", "spaces/AAAA/messages/M1",
                "text", "본문",
                "createTime", "2026-08-08T03:00:00Z",
                "sender", Map.of("name", "users/U1", "type", "HUMAN")
        );

        NormalizedEvent event = normalizer.normalizeMessages(PROJECT_ID, "engineering", List.of(message), Map.of()).get(0);

        assertThat(event.actor().id()).isEqualTo("U1");
        assertThat(event.actor().name()).isNull();
        assertThat(event.actor().email()).isNull();
    }

    @Test
    @DisplayName("sender에 displayName이 어쩌다 채워져 있으면(방어적 케이스) actorInfo보다 우선한다")
    void normalizeMessages_embeddedDisplayName_takesPriorityOverActorInfo() {
        Map<String, Object> message = Map.of(
                "name", "spaces/AAAA/messages/M1",
                "text", "본문",
                "createTime", "2026-08-08T03:00:00Z",
                "sender", Map.of("name", "users/U1", "type", "HUMAN", "displayName", "Embedded Name")
        );
        Map<String, GoogleChatRawService.PersonInfo> actorInfo =
                Map.of("users/U1", new GoogleChatRawService.PersonInfo("People API Name", "alice@example.com"));

        NormalizedEvent event = normalizer.normalizeMessages(PROJECT_ID, "engineering", List.of(message), actorInfo).get(0);

        assertThat(event.actor().name()).isEqualTo("Embedded Name");
        // 이메일은 임베디드 필드가 없으므로 actorInfo 값을 그대로 쓴다
        assertThat(event.actor().email()).isEqualTo("alice@example.com");
    }

    @Test
    @DisplayName("스레드 답글은 thread.name을 conversation_id로 쓴다 — 루트와 답글이 같은 값을 공유")
    void normalizeMessages_threadReply_usesThreadNameAsConversationId() {
        Map<String, Object> reply = Map.of(
                "name", "spaces/AAAA/messages/M2",
                "text", "동의합니다",
                "createTime", "2026-08-08T03:05:00Z",
                "sender", Map.of("name", "users/U2", "type", "HUMAN"),
                "thread", Map.of("name", "spaces/AAAA/threads/T1")
        );

        List<NormalizedEvent> events = normalizer.normalizeMessages(PROJECT_ID, "engineering", List.of(reply), Map.of());

        assertThat(events.get(0).properties()).containsEntry("conversation_id", "spaces/AAAA/threads/T1");
    }

    @Test
    @DisplayName("sender가 없으면 actor 필드 전체가 null이다")
    void normalizeMessages_missingSender_actorFieldsAreNull() {
        Map<String, Object> message = Map.of(
                "name", "spaces/AAAA/messages/M3",
                "text", "익명 메시지",
                "createTime", "2026-08-08T03:10:00Z"
        );

        NormalizedEvent event = normalizer.normalizeMessages(PROJECT_ID, "engineering", List.of(message), Map.of()).get(0);

        assertThat(event.actor().id()).isNull();
        assertThat(event.actor().name()).isNull();
        assertThat(event.actor().email()).isNull();
    }

    @Test
    @DisplayName("createTime이 깨졌으면 occurredAt은 현재 시각으로 폴백한다")
    void normalizeMessages_unparsableCreateTime_fallsBackToNow() {
        Map<String, Object> message = Map.of(
                "name", "spaces/AAAA/messages/M4",
                "text", "본문",
                "createTime", "not-a-timestamp"
        );

        NormalizedEvent event = normalizer.normalizeMessages(PROJECT_ID, "engineering", List.of(message), Map.of()).get(0);

        assertThat(event.occurredAt()).isCloseTo(Instant.now(), org.assertj.core.api.Assertions.within(5, java.time.temporal.ChronoUnit.SECONDS));
    }
}
