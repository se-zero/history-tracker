# Controller 테스트 (`@SpringBootTest` + MockMvc)

HTTP 계층 고유의 동작을 검증한다: 라우팅, 요청 역직렬화·검증, 응답 직렬화(JSON 필드),
상태 코드, 예외 → 상태 코드 매핑, 인증. 비즈니스 로직은 service mock에 위임하므로 여기서
다시 검증하지 않는다.

## 먼저: 두 패턴 중 무엇을 쓸지 — 인접 이웃을 보고 정한다

이 레포의 컨트롤러 테스트는 **서비스마다 형태가 다르다.** `@WebMvcTest`는 쓰지 않는다.
새 파일을 둘 서비스의 **기존 컨트롤러 테스트를 먼저 확인**하고 같은 패턴을 따른다.

| 서비스 | 패턴 | 쓰는 이유 |
|--------|------|----------|
| **backend** | `@SpringBootTest` + `@AutoConfigureMockMvc` (패턴 A) | 컨트롤러가 JWT 보안 필터 뒤에 있어, 인증·예외 핸들러·메시지 컨버터를 실제 설정대로 태워야 함 |
| **pipeline-worker** | `MockMvcBuilders.standaloneSetup(...)` (패턴 B) | 컨트롤러에 보안/Spring 컨텍스트 의존이 없어, 컨텍스트를 띄우지 않는 가벼운 standalone이 기존 컨벤션 |

확신이 안 서면 같은 서비스의 아무 `*ControllerTest`나 열어 보면 바로 답이 나온다.
backend는 `ProjectControllerTest`, pipeline-worker는 `CollectionTriggerControllerTest`가 대표.

## 패턴 A — backend: `@SpringBootTest` + `@AutoConfigureMockMvc`

보안 필터·예외 핸들러·메시지 컨버터가 실제 설정 그대로 적용되게 풀 컨텍스트를 띄운다.
service 계층은 **`@MockitoBean`** 으로 대체하고, 인증은 `JwtTokenService`를 mock해서 통과시킨다.

```java
package com.history.backend.project.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.history.backend.common.error.NotFoundException;
import com.history.backend.project.service.ProjectService;
import com.history.backend.security.AuthenticatedUser;
import com.history.backend.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ProjectControllerTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectService projectService;

    @MockitoBean
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUpAuthentication() {
        // 모든 요청을 USER_ID로 인증 통과시킨다
        when(jwtTokenService.validateAccessToken(anyString())).thenReturn(new AuthenticatedUser(USER_ID));
    }

    @Test
    void createProjectReturnsCreatedProject() throws Exception {
        when(projectService.createProject(USER_ID, "History Tracker", "GraphRAG backend"))
                .thenReturn(project("History Tracker", "GraphRAG backend"));

        mockMvc.perform(post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "History Tracker", "description": "GraphRAG backend" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(PROJECT_ID.toString()))
                .andExpect(jsonPath("$.name").value("History Tracker"));
    }
}
```

## 패턴 B — pipeline-worker: `MockMvcBuilders.standaloneSetup`

Spring 컨텍스트를 띄우지 않고 컨트롤러 인스턴스만 직접 만들어 MockMvc에 붙인다. 협력 service는
`mock(...)`으로, Bean Validation(`@Valid`)을 검증하려면 `LocalValidatorFactoryBean`을 수동 구성해
`setValidator(...)`로 등록한다 (standalone은 검증기가 자동 설정되지 않기 때문).

```java
package com.history.pipeline_worker.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.history.pipeline_worker.trigger.CollectionTriggerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class CollectionTriggerControllerTest {

    private CollectionTriggerService collectionTriggerService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        collectionTriggerService = mock(CollectionTriggerService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CollectionTriggerController(collectionTriggerService))
                .setValidator(validator)
                .build();
    }

    @Test
    void triggerCollection_accepted_returns202() throws Exception {
        when(collectionTriggerService.trigger(CollectionProvider.GITHUB, PROJECT_ID))
                .thenReturn(new CollectionTriggerService.TriggerResult(
                        CollectionTriggerService.TriggerStatus.ACCEPTED, "collection queued"));

        mockMvc.perform(post("/api/v1/collect/github")
                        .contentType("application/json")
                        .content("{\"projectId\":\"11111111-1111-1111-1111-111111111111\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"));
    }

    @Test
    void triggerCollection_invalidProjectId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/collect/slack")
                        .contentType("application/json")
                        .content("{\"projectId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(collectionTriggerService);   // 검증 실패 시 service 미호출
    }
}
```

> 주의: 기존 `CollectionTriggerControllerTest`는 와일드카드 import(`import static ...Mockito.*`)를
> 쓰지만, 이는 레포 전반의 명시적 import 컨벤션에서 벗어난 예외다. **새 파일은 위 예시처럼 import를
> 개별 나열**한다.

## 검증할 것들 (관찰된 케이스)

> 에러 응답 바디 모양은 서비스마다 다르다. 검증 메시지/필드를 지어내지 말고, 해당 서비스의
> 예외 핸들러(backend `GlobalExceptionHandler`는 `$.message`/`$.fields[]`, pipeline-worker는
> `$.status`/`$.message`)와 인접 테스트가 실제로 기대하는 키를 그대로 따른다.

- **정상 응답**: 상태 코드 + 핵심 JSON 필드를 `jsonPath("$.field").value(...)`로 확인.
  시각 필드는 직렬화 포맷까지 확정 (예: `"2026-05-18T01:00:00Z"`).
- **부수효과만 있는 엔드포인트** (예: `DELETE` → 204): 본문 대신
  `verify(service).deleteProject(USER_ID, PROJECT_ID)`로 service 위임을 확인.
- **service 예외 → 상태 코드 매핑**: service mock이 도메인 예외를 던지게 하고 상태 코드와
  에러 메시지를 확인. `NotFoundException`→404, `ForbiddenException`→403, `ConflictException`→409.
  ```java
  when(projectService.getProject(USER_ID, PROJECT_ID))
          .thenThrow(new NotFoundException("Project not found."));
  mockMvc.perform(get("/api/v1/projects/{projectId}", PROJECT_ID)
                  .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value("Project not found."));
  ```
- **요청 검증 실패** (예: `@NotBlank` 위반): 400 + 표준 에러 바디.
  `.andExpect(jsonPath("$.message").value("Request validation failed."))`
  `.andExpect(jsonPath("$.fields[0].field").value("name"))`
- **인증 누락** (패턴 A·backend 전용): `Authorization` 헤더 없이 호출 → 401.
  이 케이스에서는 `@BeforeEach`의 stub이 적용돼도 헤더가 없어 필터에서 막힌다.
  standalone(패턴 B)에는 보안 필터가 없으므로 이 케이스는 해당 없음.

## 팁

- 요청 본문은 Java 텍스트 블록(`"""..."""`)으로 인라인 JSON을 쓴다. DTO를 직접 만들지 않는다.
- 경로 변수는 `get("/api/v1/projects/{projectId}", PROJECT_ID)`처럼 템플릿으로 넘긴다.
- 응답 DTO가 JPA 생성 필드(id, timestamps)를 포함하면, service mock이 반환할 도메인 객체를
  `ReflectionTestUtils.setField(...)`로 채운 팩토리 메서드로 만든다 (unit-test.md 참고).
- service stub의 인자는 컨트롤러가 실제로 service에 넘기는 값과 정확히 일치해야 한다
  (안 맞으면 mock이 null을 반환해 엉뚱한 곳에서 실패한다). 컨트롤러 소스로 매핑을 확인한다.
