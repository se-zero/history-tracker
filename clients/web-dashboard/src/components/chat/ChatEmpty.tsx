import { Icons } from "@/components/Icons";
import type { Project } from "@/types/api";

// TODO(backend): highlightNodes가 실리면 그래프 하이라이트 연동. 그래프 노드 매핑은 Phase 4.
const SUGGESTED = [
  { icon: "branch", text: "왜 이 코드가 이렇게 바뀌었어?" },
  { icon: "refactor", text: "최근 머지된 리팩토링 PR들을 정리해줘" },
  { icon: "fire", text: "지난 분기 가장 논쟁이 많았던 PR은?" },
  { icon: "people", text: "이 도메인을 가장 잘 아는 사람은?" },
] as const;

export function ChatEmpty({
  project,
  onPick,
}: {
  project: Project;
  onPick: (text: string) => void;
}) {
  const iconMap = {
    branch: Icons.Branch,
    refactor: Icons.Refactor,
    fire: Icons.Fire,
    people: Icons.People,
  } as const;
  return (
    <div className="chat-empty">
      <span className="logo-mark" />
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
