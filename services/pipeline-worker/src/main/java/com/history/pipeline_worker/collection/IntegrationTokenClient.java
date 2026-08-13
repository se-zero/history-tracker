package com.history.pipeline_worker.collection;

import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// backend에 provider access token 확보(필요 시 갱신)를 요청한다. Jira 전용이던 JiraTokenClient를
// 범용화했다 — backend의 내부 토큰 API가 이미 {provider} 경로라 호출부만 provider를 받게 넓히면 된다.
// ProjectIntegrationService와 같은 패키지에 두는 이유는 이 호출이 "자격증명을 쓸 수 있게 만드는" 일이기
// 때문이다.
@Slf4j
@Component
public class IntegrationTokenClient {

    private static final String INTERNAL_SERVICE_TOKEN_HEADER = "X-Internal-Service-Token";

    private final RestClient restClient;
    private final String internalServiceToken;

    public IntegrationTokenClient(
            @Qualifier("backendRestClient") RestClient restClient,
            @Value("${security.internal-service.token}") String internalServiceToken
    ) {
        this.restClient = restClient;
        this.internalServiceToken = internalServiceToken;
    }

    /**
     * 절대 예외를 던지지 않는다 — 호출부가 세 상태만으로 분기하도록 실패를 여기서 흡수한다.
     *
     * <ul>
     *   <li>{@link TokenStatus#REFRESHED} — 갱신 완료(또는 이미 충분히 유효). 204.</li>
     *   <li>{@link TokenStatus#NOT_SUPPORTED} — 이 provider는 갱신 수단이 없다(<b>501</b>). 저장된
     *       자격증명 그대로 진행해야 한다 — '수집 제외'로 해석하면 비만료형 provider가 끊긴다.</li>
     *   <li>{@link TokenStatus#FAILED} — 연동 행 없음(<b>404</b> — 해제 직후 레이스)·backend 오류·
     *       네트워크 실패 등. 죽은 토큰으로 401을 내는 것보다 이번 수집에서 해당 provider만 제외하는
     *       편이 낫다.</li>
     * </ul>
     *
     * <p><b>404를 NOT_SUPPORTED로 읽으면 안 된다.</b> backend는 "갱신 수단 없음"(능력)과 "연동 행
     * 없음"(리소스)을 각각 501·404로 구분해 답한다. 둘 다 404이던 시절에는 해제 직후 레이스가
     * "갱신 불필요"로 읽혀 폐기된 토큰으로 수집을 진행했다.</p>
     */
    public TokenStatus ensure(UUID projectId, CollectionProvider provider) {
        try {
            restClient.post()
                    .uri("/api/v1/internal/integrations/{projectId}/{provider}/token", projectId, provider.value())
                    .header(INTERNAL_SERVICE_TOKEN_HEADER, internalServiceToken)
                    .retrieve()
                    .toBodilessEntity();
            return TokenStatus.REFRESHED;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_IMPLEMENTED) {
                return TokenStatus.NOT_SUPPORTED;
            }
            // 404는 연동 행이 없다는 뜻이라 아래 FAILED로 떨어진다 — 방금 해제된 연동을 폐기된
            // 토큰으로 수집하지 않도록, 이번 실행에서 그 provider를 건너뛰게 한다.
            log.warn("{} 토큰 확보 요청이 실패했습니다: projectId={}, status={}",
                    provider.value(), projectId, exception.getStatusCode());
            return TokenStatus.FAILED;
        } catch (RestClientException exception) {
            log.warn("{} 토큰 확보 요청이 실패했습니다: projectId={}", provider.value(), projectId, exception);
            return TokenStatus.FAILED;
        }
    }

    public enum TokenStatus {
        REFRESHED,
        NOT_SUPPORTED,
        FAILED
    }
}
