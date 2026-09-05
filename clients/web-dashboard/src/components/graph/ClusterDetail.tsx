import { useMemo } from "react";

import { Icons } from "@/components/Icons";
import { useExitPresence } from "@/hooks/useExitPresence";
import type { WorkUnit } from "@/lib/workUnitLayout";
import {
  NODE_TYPE_INFO,
  edgeCertainty,
  edgePairKey,
  type GraphEdge,
  type GraphNode,
  type GraphNodeType,
} from "@/types/graph";

interface Props {
  workUnit: WorkUnit | null;
  /** 노드 쌍 → 원본 엣지 — 구성 노드와 작업 단위를 잇는 엣지의 근거 배지를 조회하는 데 쓴다. */
  edgeByPair: Map<string, GraphEdge>;
  selectedId: string | null;
  /** 이 작업의 이웃을 불러오는 중인지 (묶음 드릴인 지연 로딩). */
  loading?: boolean;
  onSelectNode: (node: GraphNode) => void;
  onClose: () => void;
}

/**
 * 구성 노드 하나의 근거 배지 문구 — 확정(기본값)이거나 근거 엣지가 없으면 표시하지 않는다.
 * 예: "추측 0.62 · §대안 비교". section이 없으면 점수만 보여준다.
 */
function evidenceBadge(edge: GraphEdge | undefined): string | null {
  if (!edge || edgeCertainty(edge) !== "inferred") return null;
  let text = "추측";
  if (edge.confidence !== null) text += ` ${edge.confidence.toFixed(2)}`;
  if (edge.section) text += ` · §${edge.section}`;
  return text;
}

/** 세부 노드 목록 표시 순서 — 안쪽 반경(코드)부터 바깥(논의)으로. */
const GROUP_ORDER: GraphNodeType[] = ["commit", "code", "issue", "doc", "communication"];

export function ClusterDetail({
  workUnit,
  edgeByPair,
  selectedId,
  loading = false,
  onSelectNode,
  onClose,
}: Props) {
  const { shown, exiting, onExitAnimationEnd } = useExitPresence(workUnit);
  // useMemo는 조기 return보다 앞에 와야 한다(훅 순서 고정) — shown이 없을 때는 빈 배열로 계산.
  const groups = useMemo(() => {
    const map = new Map<GraphNodeType, GraphNode[]>();
    for (const member of shown?.members ?? []) {
      const list = map.get(member.node.type);
      if (list) list.push(member.node);
      else map.set(member.node.type, [member.node]);
    }
    return GROUP_ORDER.filter((t) => map.has(t)).map((t) => ({
      type: t,
      nodes: map.get(t)!,
    }));
  }, [shown]);

  if (!shown) return null;

  return (
    <div
      className={"cluster-detail" + (exiting ? " is-exiting" : "")}
      onAnimationEnd={onExitAnimationEnd}
    >
      <div className="cd-head">
        <div
          className="cd-badge"
          style={{ background: NODE_TYPE_INFO[shown.node.type].cssVar }}
        />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="cd-title">{shown.node.title}</div>
          <div className="cd-meta">
            {shown.authors.length > 0 ? shown.authors.join(", ") : shown.node.meta}
          </div>
        </div>
        <button className="icon-btn" title="닫기" onClick={onClose}>
          <Icons.X />
        </button>
      </div>

      <div className="cd-chips">
        {groups.map((g) => (
          <span key={g.type} className="cd-chip">
            <span
              className="cd-dot"
              style={{ background: NODE_TYPE_INFO[g.type].cssVar }}
            />
            {NODE_TYPE_INFO[g.type].label} {g.nodes.length}
          </span>
        ))}
      </div>

      <div className="cd-scroll">
        {/* 기본 조회는 구성 노드를 최신 N개로 자르므로 오래된 작업은 열 때 비어 있다가 채워진다. */}
        {loading && (
          <div className="cd-loading">
            <span className="spinner" />
            <span>{groups.length === 0 ? "구성을 불러오는 중…" : "더 불러오는 중…"}</span>
          </div>
        )}
        {groups.map((g) => (
          <div key={g.type} className="cd-section">
            <div className="cd-section-title">{NODE_TYPE_INFO[g.type].label}</div>
            {g.nodes.map((node) => {
              const edge = edgeByPair.get(edgePairKey(shown.node.id, node.id));
              const badge = evidenceBadge(edge);
              return (
                <button
                  key={node.id}
                  className={"cd-item" + (selectedId === node.id ? " active" : "")}
                  onClick={() => onSelectNode(node)}
                >
                  <span
                    className="cd-dot"
                    style={{ background: NODE_TYPE_INFO[node.type].cssVar }}
                  />
                  <span className="cd-item-text">{node.title}</span>
                  {badge && <span className="cd-evidence">{badge}</span>}
                </button>
              );
            })}
          </div>
        ))}
      </div>
    </div>
  );
}
