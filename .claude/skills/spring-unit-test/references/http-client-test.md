# HTTP 클라이언트 테스트 (`MockRestServiceServer` + `RestClient`)

외부 API를 호출하는 client(`GitHubAppClient`, `JiraClient`, `SlackClient`, `AiEngineQueryClient`,
`AiEngineGraphClient` 등)를 검증한다. 실제 네트워크 없이 **요청(URL·메서드·헤더·바디)이 올바른지**와
**응답·에러를 어떻게 해석/변환하는지**를 확인한다.

## 전제: client는 RestClient를 주입받아야 한다

이 패턴은 client가 `RestClient`(또는 `RestClient.Builder`)를 **생성자로 주입**받을 때만 쓸 수 있다.
테스트에서 `MockRestServiceServer.bindTo(builder)`로 빌더에 mock 서버를 붙이고, 그 빌더로 만든
`RestClient`를 client에 넘긴다.

> client가 내부에서 `RestClient.builder().build()`를 직접 만든다면 이 방식으로 테스트할 수 없다.
> 그 경우 테스트를 억지로 끼우지 말고, **client가 RestClient를 주입받도록 리팩터링할지 사용자에게
> 먼저 확인**한다 (실제 config의 `*RestClient` 빈을 주입받는 게 이 레포의 의도다).

## 패턴: fixture 헬퍼로 client + server 묶기

협력자가 있으면 `@Mock`으로 두고, client+server 생성은 `fixture()` 헬퍼와 record로 묶는다.
복잡한 properties나 응답 JSON도 헬퍼 메서드로 뽑는다.

```java
@ExtendWith(MockitoExtension.class)
class GitHubAppClientTest {

    @Mock
    private GitHubAppJwtService gitHubAppJwtService;

    @Test
    void createInstallationAccessTokenRequestsTokenWithAppJwt() {
        GitHubAppClientFixture fixture = fixture();
        when(gitHubAppJwtService.createJwt()).thenReturn("app-jwt");
        fixture.server.expect(once(), requestTo("https://api.github.test/app/installations/98765/access_tokens"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer app-jwt"))
                .andRespond(withSuccess("""
                        { "token": "installation-token", "expires_at": "2026-05-19T01:00:00Z" }
                        """, MediaType.APPLICATION_JSON));

        InstallationAccessToken result = fixture.client.createInstallationAccessToken(98765L);

        assertThat(result.token()).isEqualTo("installation-token");
        assertThat(result.expiresAt()).isEqualTo(Instant.parse("2026-05-19T01:00:00Z"));
        fixture.server.verify();   // 기대한 요청이 실제로 일어났는지 확정
    }

    // RestClient.Builder에 mock 서버를 바인딩하고, 그 빌더로 만든 client를 반환
    private GitHubAppClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubAppClient client = new GitHubAppClient(properties(), gitHubAppJwtService, builder.build());
        return new GitHubAppClientFixture(client, server);
    }

    private record GitHubAppClientFixture(GitHubAppClient client, MockRestServiceServer server) {}
}
```

## 주요 static import

```java
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
```

## 검증할 것들 (관찰된 케이스)

- **요청 정확성**: `requestTo(...)`(쿼리스트링 포함), `method(...)`, `header(...)`로 client가
  올바른 URL·메서드·인증 헤더를 보내는지 확인. 페이지네이션은 `page=1`, `page=2`를 각각
  `expect(once(), ...)`로 순서대로 기대한다.
- **응답 매핑**: 성공 JSON → 도메인 객체 필드 매핑(`assertThat(result.xxx())...`).
- **불완전 응답 거부**: 빈 토큰·빈/잘못된 만료시각 등 → client가 던지는 예외 타입·메시지 확정
  (`assertThatThrownBy(...).isInstanceOf(...).hasMessage(...)`).
- **에러 변환**: `withResourceNotFound()` 등으로 4xx/5xx를 응답시켜, client가 도메인 예외로
  변환하는지 확인 (예: ai-engine 호출 실패 → `BadGatewayException`, 상태코드 메시지 포함).
- 매 테스트 끝에 `fixture.server.verify()`로 기대 요청이 모두 소비됐는지 확인한다.

## 응답 바디 생성 팁

- 응답 JSON은 텍스트 블록(`"""..."""`)으로 인라인. 반복·대량(페이지네이션 N건)은 헬퍼에서
  `IntStream`으로 생성한다 (`repositoriesJson(100)`처럼).
- `properties()`나 인증 stub처럼 모든 테스트가 공유하는 셋업은 헬퍼/`@BeforeEach`로 모은다.
