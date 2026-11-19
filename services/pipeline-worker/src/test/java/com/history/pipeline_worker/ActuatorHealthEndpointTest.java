package com.history.pipeline_worker;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// ALB 대상 그룹 건강 검사가 보는 헬스 경로. 요청에 내부 서비스 토큰을 싣지 않는다(검사는 토큰을 못 붙인다).
// PipelineWorkerApplicationTests와 같은 DB·브로커 없는 컨텍스트에, DB·브로커 주소를 닫힌 포트로 돌린다:
// 개발 PC에 postgres·rabbitmq가 떠 있으면 readiness에 db·rabbit이 잘못 들어가도 UP으로 나와 결함을 놓친다.
@SpringBootTest(properties = {
		"security.credentials.key=dGVzdC1jcmVkZW50aWFsLWtleS0zMi1ieXRlcyEhISE=",
		"security.internal-service.token=test-internal-service-token",
		"app.outbox.relay.enabled=false",
		"spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unreachable",
		"spring.rabbitmq.host=127.0.0.1",
		"spring.rabbitmq.port=1"
})
@AutoConfigureMockMvc
@DisplayName("Actuator 헬스 경로: ALB 건강 검사")
class ActuatorHealthEndpointTest {

	@Autowired
	private MockMvc mockMvc;

	// 웹훅은 DB 아웃박스에 쓰고 relay가 나중에 브로커로 발행하므로 브로커가 끊겨도 웹훅은 받을 수 있다.
	// readiness가 DOWN이 되면 ECS가 태스크를 교체하는데(태스크가 하나일 때 ALB는 비정상이어도 그대로 보낸다),
	// DB가 끊긴 경우 교체로 해결되지 않고 진행 중 수집만 끊기며 교체 중 도착한 GitHub 배송이 실패한다.
	// 그래서 닫힌 DB·브로커 앞에서도 UP이어야 한다.
	@Test
	@DisplayName("readiness는 DB·브로커가 닫혀 있어도 UP이고 하위 지표를 노출하지 않는다")
	void readiness_withUnreachableDbAndBroker_returns200UpWithoutComponents() throws Exception {
		mockMvc.perform(get("/actuator/health/readiness"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.components").doesNotExist());
	}

	@Test
	@DisplayName("liveness는 UP이고 하위 지표를 노출하지 않는다")
	void liveness_returns200UpWithoutComponents() throws Exception {
		mockMvc.perform(get("/actuator/health/liveness"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.components").doesNotExist());
	}

	// health 외 엔드포인트(env는 설정값·시크릿 이름을 담는다)는 웹에 노출하지 않는다
	@Test
	@DisplayName("health 외 엔드포인트(env)는 노출되지 않아 404")
	void env_notExposed_returns404() throws Exception {
		mockMvc.perform(get("/actuator/env"))
				.andExpect(status().isNotFound());
	}

	// 디스커버리 페이지는 웹에 노출된 엔드포인트마다 링크를 낸다. env 404만으로는 exposure.include=health,heapdump처럼
	// 일부만 넓히는 설정을 못 잡으므로 노출 집합 전체를 고정한다(health의 링크는 health·health-path 둘).
	@Test
	@DisplayName("웹 노출 엔드포인트는 health뿐이라 디스커버리 링크가 self·health·health-path만 있다")
	void discovery_exposesOnlyHealthLinks() throws Exception {
		String body = mockMvc.perform(get("/actuator"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		Map<String, Object> links = JsonPath.read(body, "$._links");

		assertThat(links.keySet()).containsExactlyInAnyOrder("self", "health", "health-path");
	}

}
