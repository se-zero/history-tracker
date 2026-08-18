package com.history.backend.integration.service;

import java.time.Clock;
import java.time.Instant;

import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.integration.domain.AppCredential;
import com.history.backend.integration.repository.AppCredentialRepository;
import com.history.backend.jira.AtlassianProperties;
import com.history.backend.jira.service.JiraOAuthClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// Jira 봇 계정(개인정보 보고용) 앱 수준 토큰 캐싱 갱신 — JiraTokenService의 단일 행 축약본.
// 프로젝트별 여러 행이 아니라 provider 자연키 단일 행(app_credentials.ATLASSIAN)이라
// PESSIMISTIC_WRITE 잠금·이중확인을 두지 않는다: 보고 스케줄러가 단일 인스턴스·단일 스레드로만
// 갱신을 트리거하고, storeConsent(동의 엔드포인트)와의 경쟁은 마지막에 쓴 자격증명이 남는
// last-write-wins라 무해하다.
@Slf4j
@Service
public class AtlassianAppTokenService {

    private final AppCredentialRepository appCredentialRepository;
    private final JiraOAuthClient jiraOAuthClient;
    private final JiraCredentialCodec jiraCredentialCodec;
    private final AtlassianProperties atlassianProperties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public AtlassianAppTokenService(
            AppCredentialRepository appCredentialRepository,
            JiraOAuthClient jiraOAuthClient,
            JiraCredentialCodec jiraCredentialCodec,
            AtlassianProperties atlassianProperties,
            PlatformTransactionManager transactionManager
    ) {
        this(appCredentialRepository, jiraOAuthClient, jiraCredentialCodec, atlassianProperties, transactionManager, Clock.systemUTC());
    }

    AtlassianAppTokenService(
            AppCredentialRepository appCredentialRepository,
            JiraOAuthClient jiraOAuthClient,
            JiraCredentialCodec jiraCredentialCodec,
            AtlassianProperties atlassianProperties,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.appCredentialRepository = appCredentialRepository;
        this.jiraOAuthClient = jiraOAuthClient;
        this.jiraCredentialCodec = jiraCredentialCodec;
        this.atlassianProperties = atlassianProperties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // 봇 계정 동의 code를 앱 수준 자격증명으로 교환·저장 (최초 1회 또는 재동의)
    public void storeConsent(String code) {
        if (code == null || code.isBlank()) {
            throw new BadRequestException("Atlassian consent code must not be blank.");
        }

        // 외부 HTTP 호출을 트랜잭션에 넣지 않는 레포 관행 — 교환은 트랜잭션 밖에서 수행한다.
        JiraOAuthClient.JiraTokens tokens = jiraOAuthClient.exchangeCode(code);
        JiraCredential credential = new JiraCredential(
                tokens.accessToken(),
                tokens.refreshToken(),
                Instant.now(clock).plusSeconds(tokens.expiresIn())
        );
        byte[] encryptedCredential = jiraCredentialCodec.encrypt(credential);

        transactionTemplate.executeWithoutResult(status ->
                appCredentialRepository.findById(AppCredential.ATLASSIAN)
                        .ifPresentOrElse(
                                existing -> existing.updateCredential(encryptedCredential),
                                () -> appCredentialRepository.save(AppCredential.atlassian(encryptedCredential))
                        ));
    }

    // 캐시된 토큰 재사용 또는 refresh token으로 갱신
    public String getAccessToken() {
        return transactionTemplate.execute(status -> refreshIfNeeded());
    }

    private String refreshIfNeeded() {
        AppCredential appCredential = appCredentialRepository.findById(AppCredential.ATLASSIAN)
                .orElseThrow(() -> new NotFoundException("Atlassian app credential not found."));

        JiraCredential credential = jiraCredentialCodec.decrypt(appCredential.getEncryptedCredential());
        if (isReusable(credential)) {
            return credential.accessToken();
        }

        JiraOAuthClient.JiraTokens refreshed;
        try {
            refreshed = jiraOAuthClient.refresh(credential.refreshToken());
        } catch (UnauthorizedException exception) {
            // refresh token이 폐기됨 — 재발급 수단이 없어 봇 계정 재동의가 필요하다. 단일 행이라
            // Integration처럼 pending 상태로 되돌릴 대상이 없으므로 관측을 위해 로그만 남기고 전파한다.
            log.error("Atlassian bot account refresh token is invalid or revoked; re-consent required.");
            throw exception;
        }

        JiraCredential newCredential = new JiraCredential(
                refreshed.accessToken(),
                refreshed.refreshToken(),
                Instant.now(clock).plusSeconds(refreshed.expiresIn())
        );
        appCredential.updateCredential(jiraCredentialCodec.encrypt(newCredential));
        return newCredential.accessToken();
    }

    // 사용 중 만료를 막기 위해 만료 전 refreshSkew만큼 여유를 두고 갱신 대상으로 처리
    private boolean isReusable(JiraCredential credential) {
        return credential.expiresAt() != null
                && credential.expiresAt().isAfter(Instant.now(clock).plus(atlassianProperties.refreshSkew()));
    }
}
