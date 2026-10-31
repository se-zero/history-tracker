package com.history.pipeline_worker;

import com.history.pipeline_worker.outbox.OutboxCleanup;
import com.history.pipeline_worker.outbox.OutboxRelay;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

// 이 테스트 컨텍스트에는 DB·브로커가 없으므로 app.outbox.relay.enabled=false로 릴레이·청소 잡을 끈다
@SpringBootTest(properties = {
		"security.credentials.key=dGVzdC1jcmVkZW50aWFsLWtleS0zMi1ieXRlcyEhISE=",
		"security.internal-service.token=test-internal-service-token",
		"app.outbox.relay.enabled=false"
})
class PipelineWorkerApplicationTests {

	@Autowired
	private ApplicationContext context;

	@Test
	void contextLoads() {
	}

	@Test
	void outboxJobs_notRegistered_whenRelayDisabled() {
		assertThat(context.getBeanNamesForType(OutboxRelay.class)).isEmpty();
		assertThat(context.getBeanNamesForType(OutboxCleanup.class)).isEmpty();
	}

}
