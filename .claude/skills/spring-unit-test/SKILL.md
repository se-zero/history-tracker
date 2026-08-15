---
name: spring-unit-test
description: 이 프로젝트의 Spring Boot 서비스(backend, pipeline-worker)에 기존 컨벤션에 맞는 JUnit 5 테스트를 작성한다 — Mockito 단위 테스트, @SpringBootTest+MockMvc 컨트롤러 테스트, @DataJpaTest+Testcontainers 퍼시스턴스 테스트. 사용자가 "테스트 작성해줘", "테스트 코드 짜줘", "테스트 추가해줘", "TDD로 가자", "커버리지 높여줘" 등을 말하거나, services/backend·pipeline-worker의 service·controller·repository·normalizer·util에 대한 테스트 작성·추가·보완을 요청하거나, 새 Spring 기능을 TDD로 진행하거나, 특정 클래스·메서드 테스트를 언급하면 반드시 이 스킬을 사용한다. "JUnit"이나 "Mockito"를 직접 언급하지 않아도 이 레포의 Java/Spring 코드 테스트 요청이면 모두 적용된다.
---

# Spring Boot 단위·슬라이스 테스트 작성

이 스킬은 `services/backend`와 `services/pipeline-worker`(둘 다 Spring Boot 4.0.4, Java 17)에
**기존 테스트 컨벤션에 맞는** 테스트를 쓰기 위한 것이다. 목표는 새 파일이 기존 테스트와
구분되지 않을 만큼 자연스럽게 녹아드는 것이다. 임의의 "best practice"를 끌어오지 말고,
**이 레포가 이미 하는 방식**을 그대로 따른다.

## 작업 시작 전: 항상 먼저 할 일

테스트 대상이 정해지면, **추측으로 쓰기 전에** 이 두 가지를 먼저 읽는다. 이게 품질의 90%다.

1. **대상 클래스(SUT)의 실제 소스**를 읽는다. 생성자 시그니처, 의존성, 던지는 예외,
   분기 조건, null 처리, 반환 타입을 직접 확인한다. 메서드 이름만 보고 동작을 가정하지 않는다.
2. **같은 패키지/계층의 인접 테스트 1개**를 읽는다. import 스타일, SUT 생성 방식, 네이밍,
   assertion 스타일이 패키지마다 미묘하게 다르므로 가장 가까운 이웃을 모방한다.
   (예: `auth/service`에 service 테스트를 쓴다면 같은 폴더의 기존 `*ServiceTest`를 본다.)

## 테스트 유형 선택

대상이 무엇이냐에 따라 세 가지 형태가 있다. 각 형태의 상세 템플릿은 참조 파일에 있다 —
유형을 정한 뒤 **해당 참조 파일을 읽고** 그 패턴대로 작성한다.

| 대상 | 테스트 유형 | 참조 파일 |
|------|------------|----------|
| service, normalizer, util, verifier, 순수 로직 클래스 | Mockito 단위 테스트 | `references/unit-test.md` |
| controller (HTTP 계층, 직렬화, 검증, 상태코드) | MockMvc — backend는 `@SpringBootTest`, pipeline-worker는 standalone | `references/controller-test.md` |
| 외부 API client (RestClient로 GitHub/Jira/Slack/ai-engine 호출) | `MockRestServiceServer` + `RestClient` | `references/http-client-test.md` |
| repository, JPA 엔티티 매핑, 파생 쿼리, 스키마 | `@DataJpaTest` + Testcontainers | `references/persistence-test.md` |

대부분의 비즈니스 로직 검증은 **Mockito 단위 테스트**로 충분하다. controller/persistence
슬라이스는 그 계층 고유의 동작(직렬화·검증·실제 SQL·제약조건)을 검증할 때만 쓴다.
같은 로직을 단위·슬라이스에서 중복 검증하지 않는다.

## TDD vs 기존 코드 테스트

사용자의 의도에 따라 분기한다. 어느 쪽인지 불분명하면 짧게 한 번 확인한다.

### 새 기능 (TDD) — 구현이 아직 없거나 진행 중일 때

이 레포 사용자는 TDD를 선호한다. 테스트를 먼저 쓰고 **실패를 확인한 뒤** 구현으로 넘어간다.

1. 요구사항을 시나리오 목록으로 쪼갠다 (정상 경로 + 경계 + 예외).
2. 테스트를 먼저 작성한다. 아직 없는 타입/메서드를 참조하면 컴파일이 깨지는 게 정상이다.
3. `./gradlew test --tests "..."`로 **빨강(실패/컴파일 에러)** 을 확인한다 — 테스트가 실제로
   무언가를 검증하는지 보장하기 위함이다. 통과해버리면 그 테스트는 의미가 없다.
4. 그다음 구현하거나, 사용자에게 구현 단계로 넘길지 확인한다.

### 기존 코드 테스트 — 구현이 이미 있을 때

먼저 SUT 소스를 읽어 **실제 동작**을 파악하고(위 "항상 먼저 할 일"), 그 동작을 그대로
검증한다. "이렇게 동작해야 한다"가 아니라 "이렇게 동작한다"를 확정한다.
읽다가 버그로 의심되는 부분이 보이면, 통과하는 테스트로 덮지 말고 **사용자에게 알린다.**

## 어떤 시나리오를 테스트할까

기존 테스트들이 보여주는 커버리지 감각을 따른다 — 한 메서드의 행복 경로 하나가 아니라,
**동작을 가르는 분기마다** 케이스를 둔다. 관찰된 패턴:

- 정상 경로 (대표 입력 1개)
- 경계/대체 동작: null 필드 → fallback, 빈 리스트 → 빈 결과, 우선순위 필드 선택
  (예: committer date > author date), 대소문자 무시, 중복 처리
- 예외 경로: 못 찾으면 `NotFoundException`, 권한 없으면 `ForbiddenException`,
  중복이면 `ConflictException` — 그리고 그때 **부수효과가 일어나지 않음**도 검증
  (`verifyNoInteractions(...)`)
- 동시성/트랜잭션 등 비직관적 설계가 있으면 그 의도를 검증하는 케이스
  (예: concurrent insert가 이기면 재조회, soft-delete grace period 복구)

과한 케이스로 부풀리지 않는다. 각 테스트는 **하나의 명확한 동작**을 검증하고, 그 이름이
무엇을 보장하는지 말해주게 한다.

## 공통 규칙 (모든 유형 공통)

- **JUnit 5** (`org.junit.jupiter.api.Test`). **AssertJ** `assertThat(...)`를 기본 assertion으로
  쓴다. 예외는 `assertThrows(...)` 또는 AssertJ `assertThatThrownBy(...)`.
- **`@MockitoBean`** 을 쓴다 (Spring 컨텍스트 빈 대체). 구식 `@MockBean`은 쓰지 않는다.
  순수 Mockito 단위 테스트의 협력자는 `@Mock`.
- import는 와일드카드(`import ...*`) 없이 명시적으로 나열한다 (기존 파일과 동일).
- JPA가 생성하는 필드(`id`, `createdAt` 등)를 테스트에서 세팅해야 하면
  `org.springframework.test.util.ReflectionTestUtils.setField(...)`를 쓴다.
- 반복되는 SUT 생성·테스트 데이터 조립은 **private 헬퍼/팩토리 메서드**로 뽑는다
  (파일 하단에 모음). 예: `private UserService userService() { ... }`,
  `private Map<String,Object> buildCommit(...)`.
- 고정 식별자/시각은 `private static final` 상수로 둔다
  (예: `UUID USER_ID = UUID.fromString(...)`, `Instant.parse("2026-05-18T01:00:00Z")`).

### 네이밍 — 패키지의 기존 스타일을 따른다

두 가지 스타일이 공존한다. **새 파일을 둘 패키지의 이웃 테스트가 쓰는 스타일**을 그대로 쓴다.

- backend 스타일: 서술형 camelCase 메서드명, `@DisplayName` 없음.
  예: `deactivateUserSoftDeletesActiveUserAndRevokesRefreshTokens()`
- pipeline-worker(source/normalizer) 스타일: `method_scenario_expected` + 한글 `@DisplayName`.
  예: `@DisplayName("머지 커밋(parents 2개)은 결과에서 제외")`
        `void normalizeCommits_mergeCommit_filtered()`

## 실행

작성·수정 후 반드시 해당 서비스에서 테스트를 돌려 통과를 확인한다.

```bash
cd services/backend          # 또는 services/pipeline-worker
./gradlew test --tests "com.history.backend.auth.service.UserServiceTest"
```

Windows PowerShell에서는 `./gradlew` 대신 `.\gradlew`도 동작한다. 단일 클래스만 돌려
빠르게 확인하고, 마지막에 필요하면 `./gradlew test`로 전체를 돌린다.
Testcontainers 기반 persistence 테스트는 **Docker가 떠 있어야** 한다
(`@Testcontainers(disabledWithoutDocker = true)`라 Docker가 없으면 자동 skip된다).

## 마무리 체크리스트

- 대상 SUT 소스와 인접 테스트를 실제로 읽었는가
- 유형에 맞는 참조 파일의 패턴을 따랐는가
- 분기마다 케이스가 있고, 예외 케이스는 부수효과 부재까지 검증하는가
- 네이밍·import·헬퍼 스타일이 이웃 테스트와 일치하는가
- 실제로 `./gradlew test --tests ...`를 돌려 통과(또는 TDD라면 의도된 실패)를 확인했는가
