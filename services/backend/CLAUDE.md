## 패키지 구조

패키지는 기능 단위로 나눈다. `auth`, `github`, `project`, `integration`, `conversation` 아래에 `controller/service/repository/domain/dto`를 둔다. 전역 코드는 `common`, `config`, `security`, pipeline 공유 테이블은 `shared`에 둔다.

## 규칙 및 주의사항

- 다른 기능의 Repository를 직접 주입하지 말고 Service를 통해 접근한다.
- Controller에는 비즈니스 로직을 두지 않는다.
- DB 스키마는 Flyway migration으로 관리하고 JPA `ddl-auto`는 `validate`를 사용한다.
- 기능 PR마다 필요한 migration을 추가한다.
- main에 머지된 migration 파일은 수정하지 말고 새 migration으로 변경한다.
