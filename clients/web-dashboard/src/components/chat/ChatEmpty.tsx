import { Icons } from "@/components/Icons";
import type { Project } from "@/types/api";
import { SUGGESTED } from "./suggestedQuestions";

// TODO(backend): highlightNodes가 실리면 그래프 하이라이트 연동. 그래프 노드 매핑은 Phase 4.

export function ChatEmpty({
  project,
  onPick,
  disabled,
}: {
  project: Project;
  onPick: (text: string) => void;
  disabled?: boolean;
}) {
  const iconMap = {
    branch: Icons.Branch,
    refactor: Icons.Refactor,
    fire: Icons.Fire,
    people: Icons.People,
  } as const;
  return (
    <div className="chat-empty">
      {/* 로고 마크는 public/favicon.svg가 단일 출처 — 브랜드 자산이라 테마 불변. */}
      <img className="logo-mark" src="/favicon.svg" alt="" aria-hidden="true" width={44} height={44} />
      <h2>무엇을 알아볼까요?</h2>
      <p>
        {project.name}에 대해 아래 추천 질문으로 시작하거나, 직접 자연어로 물어보세요.
      </p>
      <div className="suggest-grid">
        {SUGGESTED.map((s, i) => {
          const Ic = iconMap[s.icon];
          return (
            <button
              key={i}
              className="suggest-card"
              onClick={() => onPick(s.text)}
              disabled={disabled}
            >
              <span className="sg-icon">
                <Ic size={14} />
              </span>
              <span>{s.text}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
