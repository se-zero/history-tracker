package com.history.backend.oauth.service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.history.backend.oauth.dto.OAuthGrantResponse;
import com.history.backend.oauth.repository.OAuthGrantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 연결된 앱 목록·철회. UserService가 이 서비스를 주입하므로 여기서 UserService를 참조하면
// 순환이 된다 — active user 검증은 호출하는 UserService가 먼저 끝낸다.
@Service
@RequiredArgsConstructor
public class OAuthGrantService {

    private final OAuthGrantRepository oAuthGrantRepository;
    private final RegisteredClientRepository registeredClientRepository;

    @Transactional(readOnly = true)
    public List<OAuthGrantResponse> list(UUID userId) {
        return oAuthGrantRepository.findActiveByPrincipal(userId.toString(), Instant.now()).stream()
                .map(row -> {
                    RegisteredClient client = registeredClientRepository.findById(row.registeredClientId());
                    if (client == null) {
                        return null;
                    }
                    return new OAuthGrantResponse(
                            client.getId(),
                            client.getClientId(),
                            client.getClientName(),
                            McpRegisteredClientPolicy.clientUriOf(client),
                            row.grantedAt(),
                            row.lastUsedAt());
                })
                .filter(Objects::nonNull)
                .toList();
    }

    // 지울 행이 없어도 예외 없이 끝낸다 — 두 탭에서 동시에 눌러도 오류가 나지 않게 한다
    @Transactional
    public void revoke(UUID userId, String grantId) {
        oAuthGrantRepository.deleteByPrincipalAndClient(userId.toString(), grantId);
    }
}
