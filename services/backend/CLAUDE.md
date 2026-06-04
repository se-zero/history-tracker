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
