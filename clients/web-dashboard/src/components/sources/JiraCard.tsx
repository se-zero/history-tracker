import { useEffect, useState } from "react";

import { JiraMark } from "@/components/brand/BrandMarks";
import { BusyLabel } from "@/components/ui/BusyLabel";
import { Field } from "@/components/ui/Field";
import { InlineError } from "@/components/ui/InlineError";
import { useJiraAuthorize } from "@/hooks/useIntegrationOAuth";
import { useIntegrations } from "@/hooks/useIntegrations";
import { useCompleteJiraProject, useJiraProjects, useJiraSites } from "@/hooks/useJira";
import { formatTimestamp } from "@/lib/format";

// 미연결 상태는 이 컴포넌트가 아니라 "추가 가능" 타일(SourceTileGrid)이 맡는다 — 이 카드는
// integration이 존재할 때(pending·connected)만 "연동 중" 행으로 렌더된다.
export function JiraCard({
  projectId,
  oauthError,
}: {
  projectId: string;
  oauthError?: string;
}) {
  const integrationsQuery = useIntegrations(projectId);
  const integration = integrationsQuery.data?.find((i) => i.provider === "jira");
  const pending = integration?.metadata?.["status"] === "pending_project";
  const connected = Boolean(integration) && !pending;

  const authorizeMutation = useJiraAuthorize(projectId);

  const [cloudId, setCloudId] = useState("");
  const [projectKey, setProjectKey] = useState("");

  const sitesQuery = useJiraSites(projectId, pending);
  const sites = sitesQuery.data ?? [];

  // 사이트가 1개면 자동 선택해 그 단계를 건너뛴다
  useEffect(() => {
    if (sites.length === 1 && !cloudId) {
      setCloudId(sites[0].cloudId);
    }
  }, [sites, cloudId]);

  const projectsQuery = useJiraProjects(projectId, cloudId || undefined);
  const projects = projectsQuery.data ?? [];

  const completeMutation = useCompleteJiraProject(projectId);

  const selectedSite = sites.find((site) => site.cloudId === cloudId);
  const selectedProject = projects.find((project) => project.key === projectKey);

  // 사이트 목록 조회가 실패(토큰 폐기·앱 접근 취소·네트워크 오류)하면 cloudId를 영영 못 골라
  // "연결 완료"가 계속 disabled로 남는다 — 이 경우만 "재연결 필요"로 배지를 갈아탄다.
  const reconnectNeeded = pending && sitesQuery.isError;
  // 사이트·프로젝트가 모두 선택돼 "연결 완료"를 바로 누를 수 있는 상태 — 이 순간에만 accent를 준다.
  const completeReady = Boolean(cloudId) && Boolean(projectKey);

  const badgeClass = connected ? "connected" : "warn";
  const badgeLabel = connected
    ? "연결됨"
    : reconnectNeeded
      ? "재연결 필요"
      : "프로젝트 선택 필요";

  // site_name은 Atlassian 도메인계 값(라틴)이라 mono, project_name은 한글 가능성이 있어 본문
  // 서체 — 단 project_key 폴백일 때는 라틴 코드값이라 mono가 자연스럽다.
  const siteName = integration?.metadata?.["site_name"] as string | undefined;
  const projectName = integration?.metadata?.["project_name"] as string | undefined;
  const projectKeyMeta = integration?.metadata?.["project_key"] as string | undefined;
  const usingKeyFallback = !projectName && Boolean(projectKeyMeta);
  const projectLabel = projectName ?? projectKeyMeta ?? "";

  const lastSyncedAt = integration?.lastSyncedAt
    ? formatTimestamp(integration.lastSyncedAt)
    : null;

  return (
    <div className={"source-row" + (pending ? " warn" : "")}>
      <div className="source-row-top">
        <div className="source-logo-chip">
          <JiraMark size={20} />
        </div>
        <div className="source-row-main">
          <div className="source-row-name">Jira</div>
          <div className="source-row-sub">
            {connected ? (
              <>
                <span className="mono">{siteName}</span>{" / "}
                {usingKeyFallback ? <span className="mono">{projectLabel}</span> : projectLabel}
              </>
            ) : (
              "사이트와 프로젝트를 선택하세요"
            )}
          </div>

          {oauthError && <InlineError style={{ marginTop: 10 }}>{oauthError}</InlineError>}

          {pending && (
            <>
              {authorizeMutation.isError && (
                <InlineError style={{ marginTop: 10 }}>
                  연결에 실패했어요. 잠시 후 다시 시도해 주세요.
                </InlineError>
              )}
              <div className="connect-form" style={{ marginTop: 12 }}>
                {sitesQuery.isError ? (
                  <InlineError>
                    사이트 목록을 불러오지 못했어요. 토큰이 만료됐거나 권한이 부족할 수 있어요.
                  </InlineError>
                ) : (
                  <>
                    <Field label="사이트">
                      {sites.length === 1 ? (
                        // resource-level grant로 등록된 Atlassian 앱은 동의 화면에서 이미 사이트를
                        // 선택하므로 목록이 항상 1개뿐이다 — 고를 게 없는데 select로 보이면 혼란만
                        // 준다. 다만 문서상 site(s)로 복수 표기돼 있고 account-level로 등록 방식이
                        // 바뀌면 여러 개가 돌아올 수 있어, 하드코딩 대신 개수로 분기해 그때도
                        // 자연히 select로 복귀하게 한다. 이름은 계속 보여줘 동의 화면에서 고른
                        // 사이트가 맞는지 확인할 근거로 쓴다.
                        <div className="jira-site-value">{sites[0].name}</div>
                      ) : (
                        <select
                          className="jira-select"
                          value={cloudId}
                          onChange={(e) => {
                            setCloudId(e.target.value);
                            setProjectKey("");
                          }}
                          disabled={sitesQuery.isLoading}
                        >
                          <option value="" disabled>
                            {sitesQuery.isLoading ? "불러오는 중…" : "사이트를 선택하세요"}
                          </option>
                          {sites.map((site) => (
                            <option key={site.cloudId} value={site.cloudId}>
                              {site.name}
                            </option>
                          ))}
                        </select>
                      )}
                    </Field>

                    {cloudId && (
                      <Field label="프로젝트">
                        {projectsQuery.isError ? (
                          <InlineError>프로젝트 목록을 불러오지 못했어요.</InlineError>
                        ) : (
                          <select
                            className="jira-select"
                            value={projectKey}
                            onChange={(e) => setProjectKey(e.target.value)}
                            disabled={projectsQuery.isLoading}
                          >
                            <option value="" disabled>
                              {projectsQuery.isLoading ? "불러오는 중…" : "프로젝트를 선택하세요"}
                            </option>
                            {projects.map((project) => (
                              <option key={project.key} value={project.key}>
                                {project.name}
                              </option>
                            ))}
                          </select>
                        )}
                      </Field>
                    )}
                  </>
                )}
                {completeMutation.isError && (
                  <InlineError>연결을 완료하지 못했어요. 다시 시도해 주세요.</InlineError>
                )}
              </div>
            </>
          )}
        </div>
        <div className="source-row-status">
          <span className={"src-pill " + badgeClass}>
            <span className="dot" />
            {badgeLabel}
          </span>
          {lastSyncedAt && (
            <div className="source-row-synced">
              마지막 수집 <span className="mono">{lastSyncedAt}</span>
            </div>
          )}
        </div>
      </div>

      {pending && (
        <div className="source-row-actions">
          <button
            className={"btn " + (reconnectNeeded ? "btn-primary" : "btn-ghost")}
            onClick={() => authorizeMutation.mutate()}
            disabled={authorizeMutation.isPending || authorizeMutation.isSuccess}
            aria-busy={authorizeMutation.isPending || authorizeMutation.isSuccess}
          >
            <BusyLabel
              busy={authorizeMutation.isPending || authorizeMutation.isSuccess}
              label={reconnectNeeded ? "다시 연결" : "사이트 다시 선택"}
              busyLabel="이동 중…"
            />
          </button>
          <button
            className={"btn " + (completeReady ? "btn-primary" : "btn-ghost")}
            onClick={() =>
              completeMutation.mutate({
                cloudId,
                siteName: selectedSite?.name ?? "",
                projectKey,
                projectName: selectedProject?.name ?? "",
              })
            }
            disabled={!cloudId || !projectKey || completeMutation.isPending}
            aria-busy={completeMutation.isPending}
          >
            <BusyLabel busy={completeMutation.isPending} label="연결 완료" busyLabel="연결 중…" />
          </button>
        </div>
      )}
    </div>
  );
}
