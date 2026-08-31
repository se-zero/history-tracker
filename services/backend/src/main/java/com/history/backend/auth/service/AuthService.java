package com.history.backend.auth.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.dto.GitHubCallbackRequest;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.github.GitHubAppProperties;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.dto.GitHubAccessTokenResponse;
import com.history.backend.github.dto.GitHubInstallationResponse;
import com.history.backend.github.dto.GitHubInstallationsResponse;
import com.history.backend.github.dto.GitHubUserResponse;
import com.history.backend.github.service.GitHubAppClient;
import com.history.backend.github.service.GitHubInstallationService;
import com.history.backend.github.service.GitHubOAuthClient;
import com.history.backend.github.service.GitHubUserTokenService;
import com.history.backend.security.JwtProperties;
import com.history.backend.security.JwtTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final GitHubAppProperties gitHubAppProperties;
    private final GitHubOAuthClient gitHubOAuthClient;
    private final GitHubAppClient gitHubAppClient;
    private final GitHubInstallationService gitHubInstallationService;
    private final GitHubUserTokenService gitHubUserTokenService;
    private final UserService userService;
    private final RefreshTokenService refreshTokenService;
    private final JwtTokenService jwtTokenService;
    private final JwtProperties jwtProperties;

    // GitHub OAuth 인증 URL 생성.
    // 이미 앱이 설치된 사용자도, 미설치 사용자도 동일한 OAuth authorize URL로 보낸다.
    // GitHub App의 OAuth authorize 페이지는 동의와 함께 "Install on" 옵션도 함께 제공하므로
    // 신규 사용자는 같은 흐름에서 앱 설치까지 끝낼 수 있다.
    public URI buildGitHubAuthorizeUri(String state) {
        if (gitHubAppProperties.clientId() == null || gitHubAppProperties.clientId().isBlank()) {
            throw new IllegalStateException("GitHub App client_id is not configured.");
        }
        if (gitHubAppProperties.redirectUri() == null || gitHubAppProperties.redirectUri().isBlank()) {
            throw new IllegalStateException("GitHub App redirect_uri is not configured.");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(gitHubAppProperties.authorizeUrl())
                .queryParam("client_id", gitHubAppProperties.clientId())
                .queryParam("redirect_uri", gitHubAppProperties.redirectUri())
                // 브라우저에 GitHub 세션이 남아 있으면 이미 승인한 사용자는 계정 선택 없이 자동
                // 리다이렉트된다. 로그아웃 후 다른 계정으로 로그인할 수 있도록 계정 선택 화면을 강제한다.
                .queryParam("prompt", "select_account");

        if (state != null && !state.isBlank()) {
            builder.queryParam("state", state);
        }

        return builder.encode().build().toUri();
    }

    // GitHub App 설치 페이지 URL 생성.
    // OAuth authorize는 이미 동의한 사용자에게 설치 화면 없이 자동 리다이렉트되므로,
    // 신규 워크스페이스 설치/추가에는 별도의 App installation URL을 사용한다.
    public URI buildGitHubInstallUri(String state) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(resolveInstallationUrl());

        if (state != null && !state.isBlank()) {
            builder.queryParam("state", state);
        }

        return builder.encode().build().toUri();
    }

    private String resolveInstallationUrl() {
        if (gitHubAppProperties.installationUrl() != null && !gitHubAppProperties.installationUrl().isBlank()) {
            return gitHubAppProperties.installationUrl();
        }
        if (gitHubAppProperties.appSlug() == null || gitHubAppProperties.appSlug().isBlank()) {
            throw new IllegalStateException("GitHub App installation_url or app_slug is not configured.");
        }
        return "https://github.com/apps/" + gitHubAppProperties.appSlug() + "/installations/new";
    }

    @Transactional
    public IssuedSession loginWithGitHub(GitHubCallbackRequest request) {
        // GitHub code를 user access token으로 교환
        GitHubAccessTokenResponse tokenResponse = gitHubOAuthClient.exchangeCode(request.code());
        String accessToken = requireAccessToken(tokenResponse);

        // GitHub 사용자 정보 조회
        GitHubUserResponse gitHubUser = gitHubOAuthClient.fetchUser(accessToken);

        // 내부 user 생성 또는 갱신
        User user = userService.upsertGitHubUser(gitHubUser);
        gitHubUserTokenService.save(user.getId(), tokenResponse);

        syncInstallations(user, gitHubUser, accessToken);

        // 서비스 access token 발급. refresh 원문은 컨트롤러가 httpOnly 쿠키로만 내려보낸다.
        return new IssuedSession(
                jwtTokenService.issueAccessToken(user.getId()),
                refreshTokenService.issueRefreshToken(user),
                jwtProperties.accessTokenTtl().toSeconds(),
                jwtProperties.refreshTokenTtl()
        );
    }

    private String requireAccessToken(GitHubAccessTokenResponse response) {
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new UnauthorizedException("Invalid GitHub authorization code.");
        }
        return response.accessToken();
    }

    // 바깥 트랜잭션이 기본 롤백 규칙이면 rotateRefreshToken의 noRollbackFor가 무효가 된다.
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public IssuedSession refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new UnauthorizedException("Invalid refresh token.", true);
        }
        RefreshTokenIssue issue = refreshTokenService.rotateRefreshToken(refreshToken);
        return new IssuedSession(
                jwtTokenService.issueAccessToken(issue.user().getId()),
                issue.refreshToken(),
                jwtProperties.accessTokenTtl().toSeconds(),
                jwtProperties.refreshTokenTtl()
        );
    }

    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        refreshTokenService.revokeRefreshToken(refreshToken);
    }

    // 로그인 시점에 GitHub의 설치 목록을 조회해 접근 가능한 설치를 동기화.
    // App installation 콜백(Setup URL)에만 의존하면 외부에서 추가/해제된 설치를 놓치므로
    // 로그인마다 GitHub을 진실의 원천으로 다시 동기화한다.
    // account.login이 본인 GitHub 계정과 일치하는 개인(User) 설치는 추가 호출 없이 통과시키고,
    // 그 외(조직 설치, 타인의 개인 설치)는 GitHub에 실제 접근 권한이 있는지 확인한 뒤에만 동기화한다.
    // /user/installations가 "App manager면 남의 설치도 반환한다"는 근거는 공식 문서에 없지만,
    // 틀렸을 때 운영자 계정이 모든 테넌트 설치의 멤버가 되므로 이 방어는 유지한다.
    private void syncInstallations(User user, GitHubUserResponse gitHubUser, String accessToken) {
        GitHubInstallationsResponse installations = gitHubOAuthClient.fetchInstallations(accessToken);
        // GitHub 장애 등으로 응답 자체를 못 받으면 이번 로그인에서 확인된 접근 가능 설치 목록이
        // 없다는 뜻이지, 사용자가 전부 접근권을 잃었다는 뜻이 아니다. 여기서 prune까지 돌리면
        // 장애 한 번에 멀쩡한 멤버십이 전부 사라지므로 early return으로 멤버십을 그대로 둔다.
        if (installations == null || installations.installations() == null) {
            return;
        }

        List<UUID> keptInstallationIds = new ArrayList<>();
        boolean hasUnknownAccess = false;
        boolean hasOwnPersonalInstallation = false;
        for (GitHubInstallationResponse installation : installations.installations()) {
            boolean isOwnPersonal = isOwnPersonalInstallation(installation, gitHubUser);
            hasOwnPersonalInstallation = hasOwnPersonalInstallation || isOwnPersonal;
            GitHubOAuthClient.InstallationAccess access = isOwnPersonal
                    ? GitHubOAuthClient.InstallationAccess.ACCESSIBLE
                    : checkInstallationAccess(accessToken, installation);

            if (access == GitHubOAuthClient.InstallationAccess.UNKNOWN) {
                hasUnknownAccess = true;
                continue;
            }
            if (access != GitHubOAuthClient.InstallationAccess.ACCESSIBLE) {
                continue;
            }

            GitHubInstallation upserted = gitHubInstallationService.upsertInstallation(user, installation);
            if (upserted != null) {
                keptInstallationIds.add(upserted.getId());
            }
        }

        // /user/installations는 접근 판정이 저장소 기반이라, 저장소가 0개인 설치(막 설치만 하고
        // 저장소를 아직 안 고른 경우 등)는 목록에서 통째로 빠진다. 본인 개인 설치가 목록에 없었을
        // 때만 계정 단위 조회로 한 번 더 확인한다 — 이미 있으면 불필요한 API 호출이다.
        if (!hasOwnPersonalInstallation) {
            hasUnknownAccess = syncOwnPersonalInstallationFallback(user, gitHubUser, keptInstallationIds)
                    || hasUnknownAccess;
        }

        // UNKNOWN이 하나라도 있으면 이번 로그인에서는 prune을 건너뛴다 — 일부 설치만 확인된 상태로
        // 지우면 장애 중이던 설치의 멀쩡한 멤버십까지 사라진다(위 early return과 같은 취지).
        // prune은 정리 작업일 뿐이라 다음 로그인으로 미뤄도 안전하다.
        if (hasUnknownAccess) {
            return;
        }
        gitHubInstallationService.pruneMemberships(user.getId(), keptInstallationIds);
    }

    // 계정 단위 폴백 조회 결과를 kept 집합에 반영한다. 반환값은 "prune을 건너뛰어야 하는지" —
    // 폴백 자체가 실패(예외)하면 접근 확인이 불완전한 상태라, 여기서 prune을 돌리면 이전 로그인에서
    // 이 폴백으로 등록된 멀쩡한 멤버십까지 지워진다(checkInstallationAccess의 UNKNOWN 처리와 같은 취지).
    private boolean syncOwnPersonalInstallationFallback(
            User user,
            GitHubUserResponse gitHubUser,
            List<UUID> keptInstallationIds
    ) {
        Optional<GitHubInstallationResponse> fallback;
        try {
            fallback = gitHubAppClient.fetchUserInstallation(gitHubUser.login());
        } catch (RuntimeException exception) {
            log.warn("GitHub user installation fallback failed for {}", gitHubUser.login(), exception);
            return true;
        }

        if (fallback.isEmpty() || !isOwnPersonalInstallation(fallback.get(), gitHubUser)) {
            return false;
        }

        GitHubInstallation upserted = gitHubInstallationService.upsertInstallation(user, fallback.get());
        if (upserted != null) {
            keptInstallationIds.add(upserted.getId());
        }
        return false;
    }

    private boolean isOwnPersonalInstallation(GitHubInstallationResponse installation, GitHubUserResponse gitHubUser) {
        return "User".equals(installation.account().type())
                && gitHubUser.login().equalsIgnoreCase(installation.account().login());
    }

    // checkInstallationAccess는 예외를 던지지 않는 게 계약이지만, 혹시 던지더라도 한 설치의
    // 검증 실패로 로그인 전체가 막히면 안 되므로 UNKNOWN으로 취급해 prune만 건너뛰고 로그인은 통과시킨다.
    private GitHubOAuthClient.InstallationAccess checkInstallationAccess(
            String accessToken,
            GitHubInstallationResponse installation
    ) {
        try {
            return gitHubOAuthClient.checkInstallationAccess(accessToken, installation.id());
        } catch (RuntimeException exception) {
            log.warn("GitHub installation access check failed for installation {}", installation.id(), exception);
            return GitHubOAuthClient.InstallationAccess.UNKNOWN;
        }
    }
}
