package com.history.backend.integration.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.integration.domain.Integration;
import com.history.backend.jira.service.JiraSelectionFlow;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.dto.EraseAccount;
import com.history.backend.integration.dto.PrivacyDueAccount;
import com.history.backend.integration.dto.RefreshAccount;
import com.history.backend.integration.repository.IntegrationRepository;
import com.history.backend.jira.AtlassianPersonalDataReportProperties;
import com.history.backend.jira.service.AtlassianPersonalDataClient;
import com.history.backend.jira.service.JiraClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// Jira 개인정보 보고 배치 — 봇 계정 토큰으로 ai-engine이 들고 있는 보고 대상 accountId를 Atlassian에
// 보고하고, 응답(closed 영구 삭제·updated 재조회 필요·나머지 유지)에 따라 삭제·갱신·유지를 ai-engine에
// 반영한다. 재조회는 프로젝트별 연동 토큰으로 하므로 봇 토큰과 별개의 실패 축을 갖는다.
@Slf4j
@Service
public class JiraPersonalDataReportService {

    // ai-engine GET /privacy/jira/accounts의 청크 크기 — 한 회차에 보고할 accountId 수
    private static final int FETCH_LIMIT = 90;

    private final AtlassianAppTokenService atlassianAppTokenService;
    private final AiEnginePrivacyClient privacyClient;
    private final AtlassianPersonalDataClient personalDataClient;
    private final JiraTokenService jiraTokenService;
    private final JiraClient jiraClient;
    private final IntegrationRepository integrationRepository;
    private final AtlassianPersonalDataReportProperties properties;
    private final Clock clock;

    @Autowired
    public JiraPersonalDataReportService(
            AtlassianAppTokenService atlassianAppTokenService,
            AiEnginePrivacyClient privacyClient,
            AtlassianPersonalDataClient personalDataClient,
            JiraTokenService jiraTokenService,
            JiraClient jiraClient,
            IntegrationRepository integrationRepository,
            AtlassianPersonalDataReportProperties properties
    ) {
        this(atlassianAppTokenService, privacyClient, personalDataClient, jiraTokenService, jiraClient,
                integrationRepository, properties, Clock.systemUTC());
    }

    JiraPersonalDataReportService(
            AtlassianAppTokenService atlassianAppTokenService,
            AiEnginePrivacyClient privacyClient,
            AtlassianPersonalDataClient personalDataClient,
            JiraTokenService jiraTokenService,
            JiraClient jiraClient,
            IntegrationRepository integrationRepository,
            AtlassianPersonalDataReportProperties properties,
            Clock clock
    ) {
        this.atlassianAppTokenService = atlassianAppTokenService;
        this.privacyClient = privacyClient;
        this.personalDataClient = personalDataClient;
        this.jiraTokenService = jiraTokenService;
        this.jiraClient = jiraClient;
        this.integrationRepository = integrationRepository;
        this.properties = properties;
        this.clock = clock;
    }

    public RunSummary runReportCycle() {
        String botToken;
        try {
            botToken = atlassianAppTokenService.getAccessToken();
        } catch (NotFoundException exception) {
            // 봇 계정 동의가 아직 완료되지 않은 정상 상태 — 알림 없이 이번 회차만 건너뛴다
            log.warn("Atlassian bot account consent not completed; skipping this cycle.");
            return new RunSummary(0, 0, 0, 0, 0);
        } catch (UnauthorizedException exception) {
            log.error("Atlassian bot account token is invalid or revoked; re-consent required.");
            throw exception;
        }

        Instant now = Instant.now(clock);
        Instant dueBefore = now.minus(properties.cyclePeriod());

        int reported = 0;
        int ok = 0;
        int erased = 0;
        int refreshed = 0;
        int skipped = 0;
        Set<String> previousAccountIds = null;

        while (true) {
            List<PrivacyDueAccount> dueAccounts = privacyClient.fetchDueAccounts(dueBefore, FETCH_LIMIT);
            if (dueAccounts.isEmpty()) {
                break;
            }

            Set<String> accountIds = dueAccounts.stream()
                    .map(PrivacyDueAccount::accountId)
                    .collect(Collectors.toSet());

            AtlassianPersonalDataClient.ReportOutcome outcome;
            try {
                outcome = personalDataClient.report(toReportAccounts(dueAccounts), botToken);
            } catch (AtlassianPersonalDataClient.RateLimitedException exception) {
                log.warn("Atlassian personal data report rate limited; aborting this run.");
                return new RunSummary(reported, ok, erased, refreshed, skipped);
            } catch (UnauthorizedException exception) {
                log.error("Atlassian personal data report bot credential is invalid; aborting this run.");
                return new RunSummary(reported, ok, erased, refreshed, skipped);
            } catch (BadGatewayException exception) {
                log.warn("Atlassian personal data report request failed; aborting this run.");
                return new RunSummary(reported, ok, erased, refreshed, skipped);
            }
            reported += dueAccounts.size();

            BatchResult batch = classify(dueAccounts, outcome);

            try {
                privacyClient.applyReport(now, batch.ok(), batch.erase(), batch.refresh());
                ok += batch.ok().size();
                erased += batch.erase().size();
                refreshed += batch.refresh().size();
            } catch (BadGatewayException exception) {
                // 미스탬프 계정은 pd_reported_at이 그대로라 다음 fetch에 같은 accountId로 다시 걸리고,
                // 아래 진전 없음 가드가 무한 재시도를 막는다.
                log.warn("ai-engine privacy apply request failed; will retry next cycle.");
            }
            skipped += batch.skipped();

            if (accountIds.equals(previousAccountIds)) {
                // apply 실패로 같은 accountId 집합이 그대로 재등장 — 진전이 없으므로 중단한다
                log.warn("Same due accountId set recurred without progress; stopping this run.");
                break;
            }
            previousAccountIds = accountIds;
        }

        if (erased > 0) {
            verifyActorNamesBestEffort();
        }

        return new RunSummary(reported, ok, erased, refreshed, skipped);
    }

    // 삭제 배치 끝 정합성 검증 — 이번 런의 삭제 결과는 이미 확정됐으므로 실패해도 예외를 삼킨다
    private void verifyActorNamesBestEffort() {
        try {
            int mismatches = privacyClient.verifyActorNames();
            if (mismatches > 0) {
                log.warn("Actor name verification found mismatches. count={}", mismatches);
            }
        } catch (RuntimeException exception) {
            log.error("Actor name verification failed: {}", exception.getMessage());
        }
    }

    private List<AtlassianPersonalDataClient.ReportAccount> toReportAccounts(List<PrivacyDueAccount> dueAccounts) {
        return dueAccounts.stream()
                .map(account -> new AtlassianPersonalDataClient.ReportAccount(account.accountId(), account.updatedAt()))
                .toList();
    }

    private BatchResult classify(List<PrivacyDueAccount> dueAccounts, AtlassianPersonalDataClient.ReportOutcome outcome) {
        Set<String> closedIds = new HashSet<>(outcome.closedAccountIds());
        Set<String> updatedIds = new HashSet<>(outcome.updatedAccountIds());

        List<String> ok = new ArrayList<>();
        List<EraseAccount> erase = new ArrayList<>();
        List<RefreshAccount> refresh = new ArrayList<>();
        int skipped = 0;

        for (PrivacyDueAccount account : dueAccounts) {
            String accountId = account.accountId();
            if (closedIds.contains(accountId)) {
                erase.add(new EraseAccount(accountId, "closed"));
            } else if (!updatedIds.contains(accountId)) {
                ok.add(accountId);
            }
        }

        for (String accountId : outcome.updatedAccountIds()) {
            RequeryOutcome requeryOutcome = requery(accountId);
            if (requeryOutcome.user() != null) {
                refresh.add(new RefreshAccount(
                        accountId, requeryOutcome.user().displayName(), requeryOutcome.user().emailAddress()));
            } else if (requeryOutcome.transientFailure()) {
                skipped++;
            } else {
                erase.add(new EraseAccount(accountId, "access_lost"));
            }
        }

        return new BatchResult(ok, erase, refresh, skipped);
    }

    // updated 계정을 알고 있는 프로젝트 연동을 순회하며 재조회한다. 일시 장애(BadGateway)를 확정 실패로
    // 뭉뚱그리면 아직 유효한 계정의 개인정보를 지우게 된다(erase는 되돌릴 수 없다). 그래서 성공이 하나라도
    // 있으면 즉시 refresh로 확정하고, 확정 실패(Unauthorized/NotFound)만 있으면 access_lost로 erase하되,
    // 성공 없이 일시 장애가 하나라도 섞여 있으면 이번 회차는 완전히 건너뛴다(skipped).
    private RequeryOutcome requery(String accountId) {
        List<Integration> integrations = integrationRepository.findAllByProvider(IntegrationProvider.JIRA);
        boolean transientFailure = false;

        for (Integration integration : integrations) {
            String cloudId = integration.externalRefValue(JiraSelectionFlow.CLOUD_ID);
            if (cloudId == null) {
                continue;
            }

            UUID projectId = integration.getProject().getId();
            String accessToken;
            try {
                accessToken = jiraTokenService.getAccessToken(projectId);
            } catch (UnauthorizedException | NotFoundException exception) {
                continue;
            } catch (BadGatewayException exception) {
                transientFailure = true;
                continue;
            }

            try {
                JiraClient.JiraUser user = jiraClient.getUser(cloudId, accountId, accessToken);
                return new RequeryOutcome(user, false);
            } catch (UnauthorizedException exception) {
                continue;
            } catch (BadGatewayException exception) {
                transientFailure = true;
            }
        }

        return new RequeryOutcome(null, transientFailure);
    }

    private record BatchResult(List<String> ok, List<EraseAccount> erase, List<RefreshAccount> refresh, int skipped) {
    }

    private record RequeryOutcome(JiraClient.JiraUser user, boolean transientFailure) {
    }

    public record RunSummary(int reported, int ok, int erased, int refreshed, int skipped) {
    }
}
