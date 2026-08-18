# Mockito 단위 테스트 (service / normalizer / util / 순수 로직)

협력자(다른 service, repository, client)는 mock으로 대체하고, **SUT의 로직만** 검증한다.
DB·HTTP·Spring 컨텍스트를 띄우지 않으므로 빠르다. 비즈니스 로직 검증의 기본 형태다.

## 두 가지 구성

### A) 협력자가 있는 경우 — `@ExtendWith(MockitoExtension.class)` + `@Mock`

service처럼 의존성을 주입받는 클래스. mock을 필드로 선언하고, SUT는 **팩토리 메서드**로
생성한다 (각 테스트가 stubbing을 끝낸 뒤 SUT를 만들 수 있어 유연하다).

```java
package com.history.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.common.error.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Test
    void deactivateUserSoftDeletesActiveUserAndRevokesRefreshTokens() {
        UserService userService = userService();
        User user = new User("github", "12345", "octocat@example.com", "Octocat", null);
        when(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(user));

        userService.deactivateUser(USER_ID);

        assertThat(user.getDeletedAt()).isNotNull();
        verify(refreshTokenService).revokeAllRefreshTokens(user);
    }

    @Test
    void deactivateUserRejectsDeletedOrMissingUser() {
        UserService userService = userService();
        when(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> userService.deactivateUser(USER_ID));
        verifyNoInteractions(refreshTokenService);   // 예외 시 부수효과가 없음을 확정
    }

    // SUT 팩토리 — 생성자 시그니처를 SUT 소스에서 확인하고 맞춘다
    private UserService userService() {
        return new UserService(userRepository, refreshTokenService);
    }
}
```

### B) 협력자가 없거나 실제 객체를 쓰는 경우 — `@BeforeEach`로 직접 생성

normalizer/util처럼 의존성이 없거나, 협력자의 실제 로직이 통합 동작에 본질적이라
mock하면 의미가 없는 경우. **실제 객체를 주입**하고 클래스 주석으로 그 이유를 남긴다.

```java
/**
 * GitHubNormalizer: raw GitHub API 데이터를 NormalizedEvent 목록으로 변환.
 * RefsExtractor는 실제 객체 사용 — 정규식 로직이 통합 동작에 영향을 주기 때문.
 */
class GitHubNormalizerTest {

    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";

    private GitHubNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new GitHubNormalizer(new RefsExtractor());
    }

    @Test
    @DisplayName("머지 커밋(parents 2개)은 결과에서 제외")
    void normalizeCommits_mergeCommit_filtered() {
        Map<String, Object> mergeCommit = buildCommit("sha-merge", "merge", "2024-01-01T00:00:00Z",
                List.of(Map.of("sha", "p1"), Map.of("sha", "p2")));

        assertThat(normalizer.normalizeCommits(PROJECT_ID, List.of(mergeCommit))).isEmpty();
    }

    // ── 헬퍼: 복잡한 raw 입력은 빌더 메서드로 조립해 테스트 본문을 읽기 쉽게 유지 ──
    private Map<String, Object> buildCommit(String sha, String message, String date, List<Object> parents) {
        ...
    }
}
```

## stubbing / verify 가이드

- **stub은 그 테스트가 실제로 호출하는 것만** 한다. `MockitoExtension`은 strict라서 쓰지 않는
  stub이 있으면 `UnnecessaryStubbingException`으로 실패한다. 공통 stub을 `@BeforeEach`에
  몰아넣지 말고 각 테스트에 필요한 것만 둔다.
- 같은 호출이 순차적으로 다른 값을 반환해야 하면 체이닝한다:
  `when(repo.find(...)).thenReturn(Optional.empty()).thenReturn(Optional.of(x));`
  (동시성 재조회 폴백 같은 시나리오에서 쓰인다.)
- 인자 매칭은 가능한 한 **구체적인 값**으로 한다. 값을 특정할 수 없을 때만 `any()` 등 matcher를
  쓴다. 한 호출에서 matcher와 raw 값을 섞지 않는다 (Mockito 규칙 — 섞으면 전부 matcher여야 함).
- **반환값**은 `assertThat(result)...`로, **부수효과(협력자 호출, 엔티티 상태 변경)**는
  `verify(...)` / 엔티티 getter assertion으로 검증한다. 두 가지를 의식적으로 구분한다.
- 호출되지 않아야 하는 협력자는 `verifyNoInteractions(x)` 또는 `verify(x, never())...`로 확정한다.
  특히 예외 경로에서 "조기 반환했으니 아무 일도 안 일어났다"를 증명하는 데 중요하다.

## 예외 검증 두 방식

```java
// 타입만 확인
assertThrows(NotFoundException.class, () -> service.getProject(USER_ID, PROJECT_ID));

// 타입 + 메시지/속성까지 (AssertJ)
assertThatThrownBy(() -> service.getProject(USER_ID, PROJECT_ID))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Project not found.");
```
