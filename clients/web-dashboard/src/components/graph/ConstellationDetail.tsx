import { useMemo } from "react";

import { Icons } from "@/components/Icons";
import type { Star } from "@/lib/constellation";
import { NODE_TYPE_INFO, type GraphNode, type GraphNodeType } from "@/types/graph";

interface Props {
  star: Star;
  selectedId: string | null;
  /** 이 작업의 이웃을 불러오는 중인지 (성좌 드릴인 지연 로딩). */
  loading?: boolean;
  onSelectNode: (node: GraphNode) => void;
  onClose: () => void;
}

/** 세부 노드 목록 표시 순서 — 안쪽 궤도(코드)부터 바깥(논의)으로. */
const GROUP_ORDER: GraphNodeType[] = ["commit", "code", "jira", "issue", "doc", "slack"];

export function ConstellationDetail({
  star,
  selectedId,
  loading = false,
  onSelectNode,
  onClose,
}: Props) {
  const groups = useMemo(() => {
    const map = new Map<GraphNodeType, GraphNode[]>();
    for (const sat of star.satellites) {
      const list = map.get(sat.node.type);
      if (list) list.push(sat.node);
      else map.set(sat.node.type, [sat.node]);
    }
    return GROUP_ORDER.filter((t) => map.has(t)).map((t) => ({
      type: t,
      nodes: map.get(t)!,
    }));
  }, [star]);

  return (
    <div className="galaxy-detail">
      <div className="gd-head">
        <div
          className="gd-badge"
          style={{ background: NODE_TYPE_INFO[star.node.type].cssVar }}
        />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="gd-title">{star.node.title}</div>
          <div className="gd-meta">
            {star.authors.length > 0 ? star.authors.join(", ") : star.node.meta}
          </div>
        </div>
        <button className="icon-btn" title="닫기" onClick={onClose}>
          <Icons.X />
        </button>
      </div>

      <div className="gd-chips">
        {groups.map((g) => (
          <span key={g.type} className="gd-chip">
            <span
              className="gd-dot"
              style={{ background: NODE_TYPE_INFO[g.type].cssVar }}
            />
            {NODE_TYPE_INFO[g.type].label} {g.nodes.length}
          </span>
        ))}
      </div>

      <div className="gd-scroll">
        {/* 기본 조회는 위성을 최신 N개로 자르므로 오래된 작업은 열 때 비어 있다가 채워진다. */}
        {loading && (
          <div className="gd-loading">
            <span className="spinner" />
            <span>{groups.length === 0 ? "구성을 불러오는 중…" : "더 불러오는 중…"}</span>
          </div>
        )}
        {groups.map((g) => (
          <div key={g.type} className="gd-section">
            <div className="gd-section-title">{NODE_TYPE_INFO[g.type].label}</div>
            {g.nodes.map((node) => (
              <button
                key={node.id}
                className={"gd-item" + (selectedId === node.id ? " active" : "")}
                onClick={() => onSelectNode(node)}
              >
                <span
                  className="gd-dot"
                  style={{ background: NODE_TYPE_INFO[node.type].cssVar }}
                />
                <span className="gd-item-text">{node.title}</span>
              </button>
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}
