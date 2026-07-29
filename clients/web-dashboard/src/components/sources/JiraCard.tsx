import { useEffect, useState } from "react";

import { Field } from "@/components/ui/Field";
import { InlineError } from "@/components/ui/InlineError";
import { useJiraAuthorize } from "@/hooks/useIntegrationOAuth";
import { useIntegrations } from "@/hooks/useIntegrations";
import { useCompleteJiraProject, useJiraProjects, useJiraSites } from "@/hooks/useJira";

export function JiraCard({ projectId }: { projectId: string }) {
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

  const badgeTone = connected ? "success" : pending ? "warning" : "";
  const badgeLabel = integrationsQuery.isLoading
    ? "확인 중"
    : connected
      ? "연결됨"
      : pending
        ? "프로젝트 선택 필요"
        : "미연결";

  const subText = connected
    ? (integration?.displayName ?? "Jira")
    : pending
      ? "사이트와 프로젝트를 선택하세요"
      : "선택 사항 · 이슈 맥락을 추가";

  return (
    <div className="source-card">
      <div className="src-head">
        <div className="src-logo jira">J</div>
        <div style={{ flex: 1 }}>
          <h4>Jira</h4>
          <div className="src-sub">{subText}</div>
        </div>
        <span className={"badge " + badgeTone}>
          <span className="dot" />
          {badgeLabel}
        </span>
      </div>

      {!integration && (
        <div
          style={{
            padding: "20px 0",
            textAlign: "center",
            color: "var(--fg-muted)",
            fontSize: 13,
          }}
        >
          Jira를 연결하면 이슈 맥락도 그래프에 들어갑니다.
        </div>
      )}

      {authorizeMutation.isError && (
        <InlineError>연결에 실패했어요. 잠시 후 다시 시도해 주세요.</InlineError>
      )}

      {pending && (
        <div className="connect-form">
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
      )}

      <div style={{ display: "flex", gap: 8, marginTop: "auto" }}>
        {!integration && (
          <button
            className="btn btn-primary"
            style={{ flex: 1 }}
            onClick={() => authorizeMutation.mutate()}
            disabled={integrationsQuery.isLoading || authorizeMutation.isPending}
          >
            {authorizeMutation.isPending ? "이동 중…" : "Jira 연결"}
          </button>
        )}
        {pending && (
          <>
            <button
              className="btn btn-primary"
              style={{ flex: 1 }}
              onClick={() =>
                completeMutation.mutate({
                  cloudId,
                  siteName: selectedSite?.name ?? "",
                  projectKey,
                  projectName: selectedProject?.name ?? "",
                })
              }
              disabled={!cloudId || !projectKey || completeMutation.isPending}
            >
              {completeMutation.isPending ? "연결 중…" : "연결 완료"}
            </button>
            {/* 사이트 목록 조회가 실패(토큰 폐기·앱 접근 취소·네트워크 오류)하면 cloudId를 영영 못 골라
                "연결 완료"가 계속 disabled로 남는다 — pending 행을 덮어쓸 재연결 경로가 반드시 있어야 한다. */}
            <button
              className="btn"
              onClick={() => authorizeMutation.mutate()}
              disabled={authorizeMutation.isPending}
            >
              {authorizeMutation.isPending ? "이동 중…" : "다시 연결"}
            </button>
          </>
        )}
        {connected && (
          <button className="btn" style={{ flex: 1 }} disabled>
            연결됨
          </button>
        )}
      </div>
    </div>
  );
}
