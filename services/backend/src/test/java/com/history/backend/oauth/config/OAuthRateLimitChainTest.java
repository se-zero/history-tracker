package com.history.backend.oauth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.history.backend.oauth.service.CimdClientMetadata;
import com.history.backend.oauth.service.CimdDocumentFetcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// 리미터 빈은 컨텍스트 안에서 공유되므로 테스트마다 서로 다른 CF-Connecting-IP를 쓴다.
// 요청마다 client_id를 새로 만든다 — 필터가 클라이언트 조회보다 뒤에 있으면 fetcher 호출 수가 늘어 드러난다.
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "mcp.oauth.rate-limit.per-minute=3")
@DisplayName("OAuth 인가 서버 IP별 상한 — 체인 배선")
class OAuthRateLimitChainTest {

    private static final String CODE_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final String CLIENT_IP_HEADER = "CF-Connecting-IP";
    private static final AtomicInteger CLIENT_SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CimdDocumentFetcher fetcher;

    @BeforeEach
    void stubFetcher() {
        when(fetcher.fetch(anyString())).thenAnswer(invocation -> {
            String clientIdUrl = invocation.getArgument(0);
            return new CimdClientMetadata(
                    clientIdUrl, "CIMD Test", "https://cimd.example", Set.of("http://localhost/callback"));
        });
    }

    @Test
    @DisplayName("같은 IP의 /oauth2/authorize는 3번 302, 4번째는 429 + Retry-After이고 CIMD 문서 조회도 늘지 않는다")
    void authorizeIsRejectedBeforeClientLookupOnceLimitIsExceeded() throws Exception {
        String ip = "203.0.113.11";
        for (int i = 0; i < 3; i++) {
            performAuthorize(ip).andExpect(status().isFound());
        }
        int fetchCallsAfterThird = mockingDetails(fetcher).getInvocations().size();
        assertThat(fetchCallsAfterThird).isPositive();

        performAuthorize(ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        assertThat(mockingDetails(fetcher).getInvocations().size()).isEqualTo(fetchCallsAfterThird);
    }

    @Test
    @DisplayName("상한에 걸린 IP와 다른 IP는 영향 없이 통과")
    void otherIpIsNotAffected() throws Exception {
        String limitedIp = "203.0.113.12";
        for (int i = 0; i < 3; i++) {
            performAuthorize(limitedIp).andExpect(status().isFound());
        }
        performAuthorize(limitedIp).andExpect(status().isTooManyRequests());

        performAuthorize("203.0.113.13").andExpect(status().isFound());
    }

    @Test
    @DisplayName("POST /oauth2/register 4번째는 429이고 등록 행이 늘지 않는다")
    void registerIsRejectedBeforeRowCreationOnceLimitIsExceeded() throws Exception {
        String ip = "203.0.113.14";
        for (int i = 0; i < 3; i++) {
            performRegister(ip).andExpect(status().isCreated());
        }
        int rowsAfterThird = registeredClientTotal();

        performRegister(ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        assertThat(registeredClientTotal()).isEqualTo(rowsAfterThird);
    }

    @Test
    @DisplayName("인증 없는 GET /api/v1/oauth/consent/preview는 3번 401, 4번째는 429 — 필터가 인증보다 앞")
    void consentPreviewIsRateLimitedBeforeAuthentication() throws Exception {
        String ip = "203.0.113.15";
        for (int i = 0; i < 3; i++) {
            performConsentPreview(ip).andExpect(status().isUnauthorized());
        }

        performConsentPreview(ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("IP당 예산은 하나 — authorize 2번 + register 1번 뒤 다음 요청은 429")
    void budgetIsSharedAcrossAuthorizeRegisterAndConsent() throws Exception {
        String ip = "203.0.113.16";
        performAuthorize(ip).andExpect(status().isFound());
        performAuthorize(ip).andExpect(status().isFound());
        performRegister(ip).andExpect(status().isCreated());

        performConsentPreview(ip).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("헤더가 없으면 연결 주소 기준으로 센다")
    void usesRemoteAddressWhenHeaderIsAbsent() throws Exception {
        RequestPostProcessor remote = request -> {
            request.setRemoteAddr("198.51.100.7");
            return request;
        };
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(authorizeRequest().with(remote)).andExpect(status().isFound());
        }

        mockMvc.perform(authorizeRequest().with(remote)).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("대조: /api/v1/oauth/consent/** 가 아닌 기본 체인 경로는 같은 IP로 여러 번 불러도 429가 아니다")
    void otherDefaultChainPathsAreNotRateLimited() throws Exception {
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(get("/api/v1/projects").header(CLIENT_IP_HEADER, "203.0.113.17"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── 헬퍼 ──

    private ResultActions performAuthorize(String ip) throws Exception {
        return mockMvc.perform(authorizeRequest().header(CLIENT_IP_HEADER, ip));
    }

    private MockHttpServletRequestBuilder authorizeRequest() {
        String clientId = "https://cimd.example/client-" + CLIENT_SEQ.incrementAndGet();
        return get("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", "http://localhost:4321/callback")
                .queryParam("code_challenge", CODE_CHALLENGE)
                .queryParam("code_challenge_method", "S256")
                .queryParam("state", "s1");
    }

    private ResultActions performRegister(String ip) throws Exception {
        return mockMvc.perform(post("/oauth2/register")
                .header(CLIENT_IP_HEADER, ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "client_name": "Rate Limit Test Client",
                          "redirect_uris": ["http://localhost/callback"],
                          "token_endpoint_auth_method": "none"
                        }
                        """));
    }

    private ResultActions performConsentPreview(String ip) throws Exception {
        return mockMvc.perform(get(URI.create("/api/v1/oauth/consent/preview?response_type=code&client_id=abc"))
                .header(CLIENT_IP_HEADER, ip));
    }

    private int registeredClientTotal() {
        return jdbcTemplate.queryForObject("select count(*) from oauth2_registered_client", Integer.class);
    }
}
