import { useEffect, useRef, useState } from "react";

import { Icons } from "@/components/Icons";
import { projectMark } from "@/lib/projectMark";
import type { Project } from "@/types/api";

interface Props {
  project: Project | null;
  projects: Project[];
  onSwitch: (id: string) => void;
  onNewProject: () => void;
  onReorder: (orderedIds: string[]) => void;
}

export function ProjectSwitcher({
  project,
  projects,
  onSwitch,
  onNewProject,
  onReorder,
}: Props) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  // 드래그 중인 항목과 위에 올라간 대상. 드래그 직후 발생하는 잘못된 클릭(전환)은 시간으로 무시한다.
  const [dragId, setDragId] = useState<string | null>(null);
  const [overId, setOverId] = useState<string | null>(null);
  const dragEndAtRef = useRef(0);

  useEffect(() => {
    if (!open) return;
    const h = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", h);
    return () => document.removeEventListener("mousedown", h);
  }, [open]);

  const resetDrag = () => {
    setDragId(null);
    setOverId(null);
  };

  // 드래그한 항목을 대상 위치로 옮긴 새 순서를 계산해 상위로 올린다.
  // 아래로 끌면 대상 다음에, 위로 끌면 대상 앞에 삽입한다.
  const commitReorder = (targetId: string) => {
    if (!dragId || dragId === targetId) return;
    const ids = projects.map((p) => p.id);
    const from = ids.indexOf(dragId);
    const to = ids.indexOf(targetId);
    if (from < 0 || to < 0) return;
    const next = ids.filter((id) => id !== dragId);
    let insertAt = next.indexOf(targetId);
    if (from < to) insertAt += 1;
    next.splice(insertAt, 0, dragId);
    onReorder(next);
  };

  if (!project) {
    return (
      <div className="project-switcher-wrap">
        <button className="project-switcher" onClick={onNewProject}>
          <div className="project-mark">+</div>
          <div className="project-name">
            첫 프로젝트 만들기
            <span className="role">시작하기</span>
          </div>
        </button>
      </div>
    );
  }

  return (
    <div className="project-switcher-wrap" ref={ref}>
      <button
        className={"project-switcher" + (open ? " open" : "")}
        onClick={() => setOpen((v) => !v)}
      >
        <div className="project-mark">{projectMark(project.name)}</div>
        <div className="project-name">
          {project.name}
          <span className="role">{project.description ?? "프로젝트"}</span>
        </div>
        <Icons.ChevronDown
          size={14}
          className="muted switcher-chevron"
          style={{
            transform: open ? "rotate(180deg)" : "none",
          }}
        />
      </button>
      {open && (
        <div className="project-dropdown" role="menu">
          <div className="dropdown-label">프로젝트 전환</div>
          <div className="project-dropdown-list">
            {projects.map((p) => (
              <button
                key={p.id}
                className={
                  "dropdown-item project" +
                  (p.id === project.id ? " selected" : "") +
                  (p.id === dragId ? " dragging" : "") +
                  (p.id === overId && p.id !== dragId ? " drag-over" : "")
                }
                draggable
                onDragStart={(e) => {
                  setDragId(p.id);
                  e.dataTransfer.effectAllowed = "move";
                  e.dataTransfer.setData("text/plain", p.id);
                }}
                onDragEnter={() => setOverId(p.id)}
                onDragOver={(e) => e.preventDefault()}
                onDrop={(e) => {
                  e.preventDefault();
                  commitReorder(p.id);
                  resetDrag();
                }}
                onDragEnd={() => {
                  dragEndAtRef.current = Date.now();
                  resetDrag();
                }}
                onClick={() => {
                  // 드래그 직후 브라우저가 흘리는 클릭은 전환으로 오인하지 않는다.
                  if (Date.now() - dragEndAtRef.current < 250) return;
                  onSwitch(p.id);
                  setOpen(false);
                }}
              >
                <span className="drag-handle" aria-hidden>
                  <Icons.Grip size={14} />
                </span>
                <div className="project-mark sm">{projectMark(p.name)}</div>
                <div className="project-name">
                  {p.name}
                  <span className="role">{p.description ?? "프로젝트"}</span>
                </div>
                {p.id === project.id && (
                  <Icons.Check size={13} className="check" />
                )}
              </button>
            ))}
          </div>
          <div className="dropdown-divider" />
          <button
            className="dropdown-item"
            onClick={() => {
              onNewProject();
              setOpen(false);
            }}
          >
            <span className="dropdown-icon">
              <Icons.Plus size={13} />
            </span>
            <span>새 프로젝트 만들기</span>
          </button>
        </div>
      )}
    </div>
  );
}
