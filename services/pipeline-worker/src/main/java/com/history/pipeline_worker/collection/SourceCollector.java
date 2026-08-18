package com.history.pipeline_worker.collection;

import com.history.pipeline_worker.dto.RawFetchRequest;

import java.util.Optional;

/**
 * 소스별 수집 구현이 노출하는 SPI.
 *
 * <p>새 데이터 소스를 추가할 때 편집해야 하는 곳을 이 인터페이스 구현체 하나로 좁히는 것이 목적이다.
 * 오케스트레이션 계층({@code pipeline}, {@code trigger}, {@code collection})은 provider를 알지 못하고
 * {@link SourceCollectorRegistry}로만 구현체를 찾는다. 수집 계약은 docs/normalized-event.md 참고.</p>
 */
public interface SourceCollector {

    CollectionProvider provider();

    /**
     * DB integration 행 → 수집 요청. 자격증명 복호화와 external_ref 해석은 provider가 소유한다.
     *
     * <p>구분해서 신호한다 — 호출부가 "설정 오류"와 "지금은 수집 불가"를 다르게 다루기 때문이다.</p>
     * <ul>
     *   <li>{@code Optional.empty()} — 연동은 정상이지만 지금 수집할 수 없다 (예: 만료된 GitHub 토큰).</li>
     *   <li>{@code IllegalStateException}/{@code IllegalArgumentException} — 연동 설정이 깨졌다
     *       (예: 필수 external_ref 누락). 선택 연동이면 호출부가 삼키고, 필수 연동이면 전파한다.</li>
     * </ul>
     */
    Optional<RawFetchRequest> resolveFetchRequest(ProjectIntegrationRepository.IntegrationRow integration);

    /**
     * 증분 수집 1회 — fetch → normalize → publish → checkpoint 갱신까지 provider가 소유한다.
     *
     * <p>발행 실패 예외를 삼키면 안 된다. 예외가 나야 checkpoint가 전진하지 않아 다음 수집에서
     * 재발행되고, 삼키면 그 구간이 영구 누락된다.</p>
     *
     * @return 발행한 이벤트 수
     */
    int collect(String projectId, RawFetchRequest request);
}
