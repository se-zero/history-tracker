import { useState } from "react";

import { GraphVis } from "@/components/graph/GraphVis";
import { NodeDetail } from "@/components/graph/NodeDetail";
import { Icons } from "@/components/Icons";
import { DUMMY_GRAPH } from "@/mocks/graph";
import type { Project } from "@/types/api";

export function GraphPage({ project }: { project: Project }) {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  // TODO(backend): GET /graph/project/{pid}/overview로 교체
  const data = DUMMY_GRAPH;

  return (
    <div
      style={{
        flex: 1,
        position: "relative",
        display: "flex",
        flexDirection: "column",
      }}
    >
      <div className="topbar" style={{ borderBottom: "1px solid var(--border)" }}>
        <div className="crumbs">
          <span>{project.name}</span>
          <span className="sep">/</span>
          <span className="current">그래프 탐색</span>
        </div>
        <div className="topbar-spacer" />
        <span className="muted" style={{ fontSize: 12 }}>
          {data.nodes.length} 노드 · {data.edges.length} 연결
        </span>
        <span className="badge warning">
          <span className="dot" />
          더미 데이터
        </span>
        <button className="btn btn-ghost">
          <Icons.Filter size={13} /> 필터
        </button>
      </div>
      <div style={{ flex: 1, position: "relative", minHeight: 0 }}>
        <GraphVis
          nodes={data.nodes}
          edges={data.edges}
          selectedId={selectedId}
          onSelect={(n) => setSelectedId(n.id)}
          showLegend
          showControls
          showFilters
        />
        {selectedId && (
          <NodeDetail
            node={data.nodes.find((n) => n.id === selectedId) ?? null}
            onClose={() => setSelectedId(null)}
          />
        )}
      </div>
    </div>
  );
}
