package com.history.backend.integration.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.domain.SelectionStep;

/**
 * 동의 이후 "무엇을 수집할지" 고르는 단계를 provider가 선언하는 SPI.
 *
 * <p>선택 단계가 없는 provider(Slack — 접근 가능한 전체 채널을 자동 수집)는 구현하지 않는다.
 * 구현하지 않으면 동의 직후 곧바로 확정된다.</p>
 *
 * <p>구현체는 provider 클라이언트에만 의존하는 leaf여야 한다 — {@link IntegrationService}가 이 SPI를
 * 쓰므로 여기서 다시 IntegrationService를 참조하면 순환 의존이 된다.</p>
 */
public interface IntegrationSelectionFlow {

    IntegrationProvider provider();

    /**
     * 골라야 할 단계들. **앞에서 뒤 순서**이며, 뒤 단계의 후보는 앞 단계 선택에 의존한다.
     *
     * <p>단계 수는 provider마다 다르다(1~4단). 선택적 단계를 섞을 수 있다.</p>
     */
    List<SelectionStep> steps();

    /**
     * 한 단계의 후보 목록.
     *
     * @param selected 이 단계보다 앞선 단계에서 이미 고른 값들 (external_ref 키 → 값).
     *                 부모 id로 자식 목록을 조회해야 하므로 필요하다.
     */
    List<SelectionOption> options(UUID projectId, String stepKey, Map<String, String> selected);
}
