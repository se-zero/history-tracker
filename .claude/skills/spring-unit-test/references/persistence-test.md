# Persistence 테스트 (`@DataJpaTest` + Testcontainers)

실제 PostgreSQL에 대해 repository·엔티티 매핑·파생 쿼리·제약조건을 검증한다. mock으로는
잡을 수 없는 것들(실제 SQL, unique 제약, 대소문자 무시 쿼리, soft-delete 필터, 스키마 정합성)을
확인할 때만 쓴다. H2가 아니라 **실 DB와 동일한 Postgres 컨테이너**를 띄우는 이유는 Flyway
migration과 Postgres 고유 동작을 그대로 검증하기 위함이다.

## 정형 보일러플레이트

아래 클래스 어노테이션·컨테이너·`@DynamicPropertySource` 블록은 persistence 테스트마다
**그대로 복사**한다. 인접한 기존 `*PersistenceTest` / `*SchemaTest`에서 가져오는 게 가장 안전하다.

```java
package com.history.backend.project.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.project.domain.Project;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)   // Docker 없으면 자동 skip
@Transactional
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)  // 임베디드 DB로 대체 금지
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration")  // 실제 migration 적용
class ProjectPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ProjectPersistenceTest::postgresJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static String postgresJdbcUrl() {
        // stringtype=unspecified: enum/uuid 등을 문자열로 바인딩할 때 Postgres 캐스팅 문제 회피
        return postgres.getJdbcUrl() + "&stringtype=unspecified";
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Test
    void saveAndFindProject() {
        User owner = userRepository.save(new User("github", "2001", "owner@example.com", "Owner", null));
        Project project = projectRepository.save(new Project(owner, "History Tracker", "GraphRAG backend"));

        assertThat(projectRepository.findById(project.getId())).contains(project);
        assertThat(projectRepository.findAllByOwner_IdOrderByCreatedAtDesc(owner.getId()))
                .containsExactly(project);
        assertThat(projectRepository.existsByOwnerIdAndNameIgnoreCase(owner.getId(), "history tracker"))
                .isTrue();
    }
}
```

## 검증할 것들

- **저장 후 조회**: `save` → `findById(...)`가 같은 엔티티를 담은 `Optional`을 반환
  (`.contains(project)`). 파생 쿼리(`findAllBy...OrderBy...`)는 정렬·필터까지 확인.
- **삭제**: `delete` + `flush` 후 `findById(...).isEmpty()`, 목록 쿼리 `.isEmpty()`,
  존재 체크 쿼리 `.isFalse()`.
- **쿼리 의미론**: 대소문자 무시(`...IgnoreCase`), 특정 id 제외(`...ExcludingId`),
  소유자 스코프 등 파생 쿼리 이름이 약속하는 동작을 직접 검증.
- **스키마/제약조건** (`*SchemaTest`): unique 제약 위반 시 예외, NOT NULL, FK cascade 등.
  실제 DB라서 이런 제약이 진짜로 동작한다.

## 주의

- `findById`로 조회한 엔티티가 영속성 컨텍스트 캐시에서 와 항상 같은 인스턴스로 보일 수 있다.
  쓴 값이 **DB에 실제 반영**됐는지 보려면 `saveAndFlush` 후 검증하거나, 별도 쿼리로 다시 읽는다
  (삭제 테스트가 `saveAndFlush` + `flush`를 쓰는 이유).
- FK가 있는 엔티티는 **부모를 먼저 저장**한다 (예: `Project` 전에 `User` owner 저장).
- 테스트당 식별자(provider user id, 이메일 등)는 겹치지 않게 다른 값을 쓴다
  (`"2001"`, `"2002"`, ...). unique 제약 충돌로 인접 테스트가 깨지는 걸 막는다.
- 이건 **느린 테스트**다(컨테이너 기동). 단순 로직은 여기서 검증하지 말고 Mockito 단위
  테스트로 옮긴다. persistence 테스트는 "실제 DB여야만 검증되는 것"에 한정한다.
- pipeline-worker도 같은 패턴이며, `checkpoints`·`webhook_deliveries`처럼 backend와 공유하는
  테이블을 다룬다. 인접한 `CheckpointRepositoryTest` 등을 참고한다.
