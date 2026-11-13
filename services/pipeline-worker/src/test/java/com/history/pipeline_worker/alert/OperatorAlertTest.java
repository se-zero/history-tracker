package com.history.pipeline_worker.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OperatorAlertTest {

    private static final String WEBHOOK_URL = "https://hooks.slack.test/services/T000/B000/secret-token-xyz";
    private static final Instant BASE_INSTANT = Instant.parse("2026-11-13T00:00:00Z");

    private Logger alertLogger;
    private Level originalLogLevel;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void attachLogAppender() {
        alertLogger = (Logger) LoggerFactory.getLogger(OperatorAlert.class);
        originalLogLevel = alertLogger.getLevel();
        alertLogger.setLevel(Level.INFO);
        logs = new ListAppender<>();
        logs.start();
        alertLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogAppender() {
        alertLogger.detachAppender(logs);
        alertLogger.setLevel(originalLogLevel);
    }

    @Test
    void postsTextBodyWithPrefixToConfiguredUrl() {
        Fixture fixture = fixture(WEBHOOK_URL);
        fixture.server.expect(once(), requestTo(WEBHOOK_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"text\":\"[pipeline-worker] 행 17이 계속 실패합니다\"}", true))
                .andRespond(withSuccess());

        fixture.alert.notify("relay-failing", "행 17이 계속 실패합니다");

        fixture.server.verify();
    }

    @Test
    void suppressesSameKindWithinOneHour() {
        Fixture fixture = fixture(WEBHOOK_URL);
        fixture.server.expect(once(), requestTo(WEBHOOK_URL)).andRespond(withSuccess());

        fixture.alert.notify("relay-failing", "첫 번째");
        fixture.clock.advance(Duration.ofMinutes(10));
        fixture.alert.notify("relay-failing", "두 번째");

        fixture.server.verify();
    }

    @Test
    void sendsAgainOnceOneHourHasPassed() {
        Fixture fixture = fixture(WEBHOOK_URL);
        fixture.server.expect(once(), requestTo(WEBHOOK_URL)).andRespond(withSuccess());

        fixture.alert.notify("relay-failing", "첫 번째");
        fixture.clock.advance(Duration.ofMinutes(59).plusSeconds(59));
        fixture.alert.notify("relay-failing", "59분 59초 — 아직 억제");
        fixture.server.verify();

        fixture.server.reset();
        fixture.server.expect(once(), requestTo(WEBHOOK_URL)).andRespond(withSuccess());
        fixture.clock.advance(Duration.ofSeconds(1));
        fixture.alert.notify("relay-failing", "정확히 1시간 — 전송");

        fixture.server.verify();
    }

    @Test
    void doesNotSuppressDifferentKinds() {
        Fixture fixture = fixture(WEBHOOK_URL);
        fixture.server.expect(times(2), requestTo(WEBHOOK_URL)).andRespond(withSuccess());

        fixture.alert.notify("relay-failing", "실패 행");
        fixture.alert.notify("relay-backlog", "미발행 적체");

        fixture.server.verify();
    }

    @Test
    void doesNotThrowAndRetriesNextCallWhenResponseIs4xx() {
        Fixture fixture = fixture(WEBHOOK_URL);
        fixture.server.expect(times(2), requestTo(WEBHOOK_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatCode(() -> fixture.alert.notify("relay-failing", "첫 시도")).doesNotThrowAnyException();
        assertThatCode(() -> fixture.alert.notify("relay-failing", "곧바로 재시도")).doesNotThrowAnyException();

        fixture.server.verify();
    }

    @Test
    void doesNotThrowAndRetriesNextCallWhenResponseIs5xx() {
        Fixture fixture = fixture(WEBHOOK_URL);
        fixture.server.expect(times(2), requestTo(WEBHOOK_URL)).andRespond(withServerError());

        assertThatCode(() -> fixture.alert.notify("relay-failing", "첫 시도")).doesNotThrowAnyException();
        assertThatCode(() -> fixture.alert.notify("relay-failing", "곧바로 재시도")).doesNotThrowAnyException();

        fixture.server.verify();
    }

    @Test
    void doesNotThrowAndRetriesNextCallWhenConnectionFails() {
        Fixture fixture = fixture(WEBHOOK_URL);
        fixture.server.expect(times(2), requestTo(WEBHOOK_URL))
                .andRespond(request -> {
                    throw new IOException("connection reset");
                });

        assertThatCode(() -> fixture.alert.notify("relay-failing", "첫 시도")).doesNotThrowAnyException();
        assertThatCode(() -> fixture.alert.notify("relay-failing", "곧바로 재시도")).doesNotThrowAnyException();

        fixture.server.verify();
    }

    @Test
    void makesNoHttpCallWhenUrlIsEmpty() {
        Fixture fixture = fixture("");

        fixture.alert.notify("relay-failing", "행 17");

        // 기대 요청이 하나도 없으므로 HTTP 호출이 있었다면 MockRestServiceServer가 실패시킨다.
        fixture.server.verify();
        assertThat(alertLogMessages()).containsExactly("ALERT(relay-failing): [pipeline-worker] 행 17");
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.ERROR);
    }

    @Test
    void makesNoHttpCallWhenUrlIsBlank() {
        Fixture fixture = fixture("   ");

        fixture.alert.notify("relay-failing", "행 17");

        fixture.server.verify();
        assertThat(alertLogMessages()).containsExactly("ALERT(relay-failing): [pipeline-worker] 행 17");
    }

    @Test
    void suppressesAlertLogForSameKindWithinOneHourWhenUrlIsEmpty() {
        Fixture fixture = fixture("");

        fixture.alert.notify("relay-failing", "첫 번째");
        fixture.clock.advance(Duration.ofMinutes(30));
        fixture.alert.notify("relay-failing", "두 번째");
        assertThat(alertLogMessages()).hasSize(1);

        fixture.clock.advance(Duration.ofMinutes(30));
        fixture.alert.notify("relay-failing", "세 번째");

        assertThat(alertLogMessages()).hasSize(2);
        fixture.server.verify();
    }

    @Test
    void logsAlertLineOnlyForActualSendWhenUrlIsSet() {
        Fixture fixture = fixture(WEBHOOK_URL);
        fixture.server.expect(once(), requestTo(WEBHOOK_URL)).andRespond(withSuccess());

        fixture.alert.notify("relay-failing", "행 17");
        fixture.alert.notify("relay-failing", "억제되는 호출");

        assertThat(alertLogMessages()).containsExactly("ALERT(relay-failing): [pipeline-worker] 행 17");
        fixture.server.verify();
    }

    @Test
    void neverLogsWebhookUrlOnSuccessPath() {
        Fixture fixture = fixture(WEBHOOK_URL);
        fixture.server.expect(once(), requestTo(WEBHOOK_URL)).andRespond(withSuccess());

        fixture.alert.notify("relay-failing", "행 17");

        assertThat(alertLogMessages()).isNotEmpty();
        assertThat(allLogText()).doesNotContain(WEBHOOK_URL).doesNotContain("secret-token-xyz");
    }

    @Test
    void neverLogsWebhookUrlOnFailurePaths() {
        Fixture fixture = fixture(WEBHOOK_URL);
        fixture.server.expect(once(), requestTo(WEBHOOK_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        fixture.server.expect(once(), requestTo(WEBHOOK_URL)).andRespond(withServerError());
        fixture.server.expect(once(), requestTo(WEBHOOK_URL)).andRespond(request -> {
            throw new IOException("connection reset");
        });

        fixture.alert.notify("relay-failing", "4xx");
        fixture.alert.notify("relay-failing", "5xx");
        fixture.alert.notify("relay-failing", "연결 오류");

        fixture.server.verify();
        assertThat(logs.list).isNotEmpty();
        assertThat(allLogText()).doesNotContain(WEBHOOK_URL).doesNotContain("secret-token-xyz");
    }

    private List<String> alertLogMessages() {
        return logs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("ALERT("))
                .toList();
    }

    // 메시지뿐 아니라 예외(원인 체인 포함)의 메시지·스택까지 훑는다 — URL은 예외 메시지로도 새어 나간다.
    private String allLogText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : logs.list) {
            text.append(event.getFormattedMessage()).append('\n');
            IThrowableProxy throwable = event.getThrowableProxy();
            while (throwable != null) {
                text.append(throwable.getClassName()).append(": ").append(throwable.getMessage()).append('\n');
                throwable = throwable.getCause();
            }
        }
        return text.toString();
    }

    private Fixture fixture(String webhookUrl) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MutableClock clock = new MutableClock(BASE_INSTANT);
        return new Fixture(new OperatorAlert(builder.build(), webhookUrl, clock), server, clock);
    }

    private record Fixture(OperatorAlert alert, MockRestServiceServer server, MutableClock clock) {
    }

    // 실제 흐름을 그대로 타되, 시각만 테스트가 직접 이동시키는 Clock
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
