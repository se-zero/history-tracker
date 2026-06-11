## 패키지 구조

패키지는 기능 단위로 나눈다. `auth`, `github`, `project`, `integration`, `conversation` 아래에 `controller/service/repository/domain/dto`를 둔다. 전역 코드는 `common`, `config`, `security`, pipeline 공유 테이블은 `shared`에 둔다.

## 규칙 및 주의사항

- 다른 기능의 Repository를 직접 주입하지 말고 Service를 통해 접근한다.
- Controller에는 비즈니스 로직을 두지 않는다.
- 인증 사용자 ID를 받는 비공개 API/service는 비즈니스 처리 전에 `UserService.getActiveUser()` 또는 이를 호출하는 상위 service를 통해 active user를 검증한다.
- soft-deleted user는 grace period 복구 대상일 수 있지만, 복구 전에는 비공개 API 접근과 refresh token 재발급을 허용하지 않는다.
- DB 스키마는 Flyway migration으로 관리하고 JPA `ddl-auto`는 `validate`를 사용한다.
- 기능 PR마다 필요한 migration을 추가한다.
- main에 머지된 migration 파일은 수정하지 말고 새 migration으로 변경한다.

## 주석 규칙

### 함수 주석

- 주요 함수, public 함수, 복잡한 private 함수에는 역할을 명사형으로 짧게 작성한다.
  - 예: `// refresh token 1회용 rotation (사용된 토큰 폐기 후 재발급)`, `// 활성(미탈퇴) 사용자 조회`
- 함수 내부 구현을 반복 설명하지 않는다.
- getter/setter, 단순 위임 함수(Controller 메서드 포함), 이름만으로 역할이 명확한 함수에는 주석을 달지 않는다.
- 외부 시스템과 공유하는 테이블의 엔티티, 비직관적 설계가 있는 클래스에는 클래스 주석으로 맥락을 남긴다.
  - 예: `// pipeline-worker 수집 진행 커서 — (project, provider, cursor_key) 복합키 공유 테이블`

### 코드 내부 주석

- "무엇을 하는지"보다 "왜 이렇게 처리하는지"를 우선 설명한다. 다음 지점에만 짧게 추가한다.
  - 동시성 처리: 비관적 잠금, double-checked locking, `ON CONFLICT DO NOTHING` 후 재조회 폴백 등
  - 트랜잭션 설계: 외부 API 호출을 트랜잭션 밖으로 분리하는 이유, `Propagation.MANDATORY` 사용 이유, batch 단위 트랜잭션 분리 등
  - 보안 처리: SSRF 방어, 타이밍 공격 방지 비교, hash 저장, 방어적 복사 등
  - 외부 API 특성: 오류 응답을 특정 HTTP 상태로 변환하는 이유, 비표준 응답 처리(예: Slack은 실패도 200 응답) 등
- 어노테이션이나 코드가 이미 말하는 내용을 반복하는 라인 주석은 추가하지 않는다.
- 코드만으로 단정할 수 없는 이유는 추측해서 적지 않는다. 잘못된 주석은 없는 것보다 나쁘다.

### 주석을 생략하는 곳

- 단순 CRUD service 메서드, Spring Data 파생 쿼리, DTO/record, 표준 패턴(enum converter, `@Embeddable` 복합키, 단순 빈 등록 config)

