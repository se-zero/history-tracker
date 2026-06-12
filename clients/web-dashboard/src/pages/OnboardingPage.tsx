import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";

import { Icons } from "@/components/Icons";
import { createProject } from "@/api/projects";
import { Topbar } from "@/components/shell/Topbar";
import type { Project } from "@/types/api";

export function OnboardingPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");

  const mutation = useMutation({
    mutationFn: () => createProject({ name, description: description || undefined }),
    onSuccess: (project) => {
      // 새 프로젝트를 캐시에 즉시 반영한다. invalidateQueries는 백그라운드 refetch라,
      // navigate 직후 AppShell이 stale한 빈 목록([])을 보고 /onboarding으로 되튕기는 것을 막는다.
      queryClient.setQueryData<Project[]>(["projects"], (prev) =>
        prev ? [project, ...prev] : [project],
      );
      queryClient.invalidateQueries({ queryKey: ["projects"] });
      navigate(`/projects/${project.id}/chat`, { replace: true });
    },
  });

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return;
    mutation.mutate();
  };

  return (
    <div className="app no-sidebar">
      <div className="main">
        <Topbar crumbs={["History Tracker", "시작하기"]} />
        <div className="onboarding">
          <h1>첫 프로젝트를 만들어 보세요</h1>
          <p className="lead">
            분석할 단위(서비스 또는 팀)를 만들면, 연동된 소스들로부터 자동으로 그래프가
            만들어집니다.
          </p>
          <div className="step-grid">
            <StepCard
              num="STEP 01"
              title="프로젝트 생성"
              body={`예: "Payments Platform", "Auth Team" — 하나의 컨텍스트 경계를 정합니다.`}
              icon={<Icons.Layers size={22} />}
            />
            <StepCard
              num="STEP 02"
              title="데이터 소스 연동"
              body="GitHub은 필수, Jira와 Slack은 선택. 권한이 있는 워크스페이스만 보입니다."
              icon={<Icons.Plug size={22} />}
            />
            <StepCard
              num="STEP 03"
              title="질문하기"
              body={`"왜 이렇게 짰지?"를 자연어로 던지면, 출처와 함께 맥락을 돌려드립니다.`}
              icon={<Icons.Sparkle size={22} />}
            />
          </div>

          <form
            onSubmit={submit}
            style={{
              display: "flex",
              flexDirection: "column",
              gap: 12,
              maxWidth: 480,
              margin: "32px auto 0",
            }}
          >
            <div className="field">
              <label>프로젝트 이름</label>
              <input
                autoFocus
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="Payments Platform"
              />
            </div>
            <div className="field">
              <label>설명 (선택)</label>
              <input
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="결제 도메인 전반(주문, 정산, 환불)"
              />
            </div>
            {mutation.isError && (
              <div style={{ color: "var(--danger)", fontSize: 12 }}>
                프로젝트를 만들지 못했어요. 다시 시도해 주세요.
              </div>
            )}
            <button
              type="submit"
              className="btn btn-primary btn-lg"
              disabled={!name.trim() || mutation.isPending}
            >
              <Icons.Plus size={14} />{" "}
              {mutation.isPending ? "만드는 중…" : "첫 프로젝트 만들기"}
            </button>
          </form>
        </div>
      </div>
    </div>
  );
}

function StepCard({
  num,
  title,
  body,
  icon,
}: {
  num: string;
  title: string;
  body: string;
  icon: React.ReactNode;
}) {
  return (
    <div className="step-card">
      <div className="step-num">{num}</div>
      <h3>{title}</h3>
      <p>{body}</p>
      <div className="step-visual">{icon}</div>
    </div>
  );
}
