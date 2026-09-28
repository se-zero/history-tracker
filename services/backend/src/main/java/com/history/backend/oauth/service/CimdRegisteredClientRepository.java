package com.history.backend.oauth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

// CIMD client_id(https URL)는 사전 등록 없이 인가 요청에 실려 오는데, Spring 인가 코드·토큰
// 서비스는 registered_client_id로 JDBC 조회를 하므로 실제 행이 있어야 한다 — 문서를 읽어 조립한
// RegisteredClient를 delegate에 upsert하는 그림자 행으로 그 요구를 충족시킨다.
// 문서가 영구 무효(Invalid)면 null(문서 주인이 문서를 내리면 그 순간 클라이언트를 모르는 것으로
// 끊는다), 일시 장애(Unavailable)면 이미 저장된 그림자 행으로 폴백해 인증 흐름을 계속 진행한다.
@Slf4j
public class CimdRegisteredClientRepository implements RegisteredClientRepository {

    private final RegisteredClientRepository delegate;
    private final CimdDocumentFetcher fetcher;
    private final McpRegisteredClientPolicy policy;
    private final Clock clock;

    public CimdRegisteredClientRepository(
            RegisteredClientRepository delegate, CimdDocumentFetcher fetcher, McpRegisteredClientPolicy policy, Clock clock) {
        this.delegate = delegate;
        this.fetcher = fetcher;
        this.policy = policy;
        this.clock = clock;
    }

    public static String shadowId(String clientIdUrl) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(clientIdUrl.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        delegate.save(registeredClient);
    }

    @Override
    public RegisteredClient findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        if (!clientId.startsWith("https://")) {
            return delegate.findByClientId(clientId);
        }

        CimdClientMetadata metadata;
        try {
            metadata = fetcher.fetch(clientId);
        } catch (CimdDocumentUnavailableException e) {
            // 연결 실패를 진단할 유일한 단서가 로그다 — URL과 사유만 남기고 문서 본문은 남기지 않는다.
            RegisteredClient shadow = delegate.findById(shadowId(clientId));
            log.warn("CIMD 문서를 가져오지 못해 {}: client_id={}, 사유={}",
                    shadow != null ? "저장된 그림자 행으로 진행합니다" : "클라이언트를 인식하지 못했습니다",
                    clientId, e.getMessage());
            return shadow;
        } catch (CimdDocumentInvalidException e) {
            log.info("CIMD 문서가 무효해 클라이언트를 거부합니다: client_id={}, 사유={}", clientId, e.getMessage());
            return null;
        }

        String shadowId = shadowId(clientId);
        RegisteredClient existing = delegate.findById(shadowId);
        RegisteredClient.Builder builder = RegisteredClient.withId(shadowId)
                .clientId(clientId)
                .clientName(metadata.clientName())
                .clientIdIssuedAt(existing != null ? existing.getClientIdIssuedAt() : Instant.now(clock))
                .redirectUris(uris -> uris.addAll(metadata.redirectUris()));
        RegisteredClient assembled = policy.apply(builder, metadata.clientUri()).build();

        if (existing == null || changed(existing, assembled)) {
            try {
                delegate.save(assembled);
            } catch (DataIntegrityViolationException e) {
                // 같은 URL의 최초 등록이 두 요청에서 동시에 일어나면 한쪽 INSERT가 PK 중복으로 실패한다 —
                // 행은 이미 있으므로 조립 결과로 그대로 진행한다.
                log.debug("CIMD 그림자 행이 동시에 저장돼 이번 저장을 건너뜁니다: client_id={}", clientId);
            }
        }
        return assembled;
    }

    // equals()로 비교하지 않는 이유 — JDBC가 채우는 clientIdIssuedAt 등 문서와 무관한 값 때문에
    // 실제로는 바뀐 게 없어도 매 요청 UPDATE가 발생한다.
    private boolean changed(RegisteredClient existing, RegisteredClient assembled) {
        return !Objects.equals(existing.getRedirectUris(), assembled.getRedirectUris())
                || !Objects.equals(existing.getClientName(), assembled.getClientName())
                || !Objects.equals(McpRegisteredClientPolicy.clientUriOf(existing), McpRegisteredClientPolicy.clientUriOf(assembled));
    }
}
