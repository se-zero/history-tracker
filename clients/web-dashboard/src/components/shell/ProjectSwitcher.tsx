import { useEffect, useRef, useState } from "react";

import { Icons } from "@/components/Icons";
import { projectMark } from "@/lib/projectMark";
import type { Project } from "@/types/api";

interface Props {
  project: Project | null;
  projects: Project[];
  onSwitch: (id: string) => void;
  onNewProject: () => void;
  onOpenSettings: () => void;
}

export function ProjectSwitcher({
  project,
  projects,
  onSwitch,
  onNewProject,
  onOpenSettings,
}: Props) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const h = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", h);
    return () => document.removeEventListener("mousedown", h);
  }, [open]);

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
          className="muted"
          style={{
            transition: "transform 0.15s",
            transform: open ? "rotate(180deg)" : "none",
          }}
        />
      </button>
      {open && (
        <div className="project-dropdown" role="menu">
          <div className="dropdown-label">프로젝트 전환</div>
          {projects.map((p) => (
            <button
              key={p.id}
              className={
                "dropdown-item project" + (p.id === project.id ? " selected" : "")
              }
              onClick={() => {
                onSwitch(p.id);
                setOpen(false);
              }}
            >
              <div className="project-mark sm">{projectMark(p.name)}</div>
              <div className="project-name">
                {p.name}
                <span className="role">{p.description ?? "프로젝트"}</span>
              </div>
              {p.id === project.id && <Icons.Check size={13} className="check" />}
            </button>
          ))}
          <div className="dropdown-divider" />
          <button
            className="dropdown-item"
            onClick={() => {
              onOpenSettings();
              setOpen(false);
            }}
          >
            <span className="dropdown-icon">
              <Icons.Settings size={13} />
            </span>
            <span>현재 프로젝트 설정</span>
          </button>
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
