package com.history.pipeline_worker.collection;

/**
 * 수집이 도는 사이 그 연동(integrations 행)이 해제돼 더 진행하면 안 될 때 던진다.
 *
 * <p>장애가 아니라 정상적인 중단 신호다 — 호출부는 ERROR가 아니라 INFO로 처리하고,
 * 이미 지워진 커서·그래프를 되살리지 않도록 발행·커서 쓰기 앞에서 막는다.</p>
 */
public class IntegrationDisconnectedException extends RuntimeException {

    public IntegrationDisconnectedException(String message) {
        super(message);
    }
}
