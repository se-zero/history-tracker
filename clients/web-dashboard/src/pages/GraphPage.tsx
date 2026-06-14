import { useState } from "react";
import { useQuery } from "@tanstack/react-query";

import { GraphVis } from "@/components/graph/GraphVis";
import { NodeDetail } from "@/components/graph/NodeDetail";
import { Icons } from "@/components/Icons";
import { StatusView } from "@/components/StatusView";
import { getProjectGraph } from "@/api/graph";
import type { Project } from "@/types/api";

export function GraphPage({ project }: { project: Project }) {
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const graphQuery = useQuery({
    queryKey: ["graph", project.id],
    queryFn: () => getProjectGraph(project.id),
  });

  const data = graphQuery.data;

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
        {data && (
          <span className="muted" style={{ fontSize: 12 }}>
            {data.nodes.length} 노드 · {data.edges.length} 연결
          </span>
        )}
        <button className="btn btn-ghost">
          <Icons.Filter size={13} /> 필터
        </button>
      </div>
      <div style={{ flex: 1, position: "relative", minHeight: 0 }}>
        {graphQuery.isLoading ? (
          <StatusView tone="loading" description="그래프를 불러오는 중…" />
        ) : graphQuery.isError ? (
          <StatusView
            tone="error"
            title="그래프를 불러오지 못했어요"
            description="다시 시도해 주세요."
            action={
              <button className="btn" onClick={() => graphQuery.refetch()}>
                다시 시도
              </button>
            }
          />
        ) : !data || data.nodes.length === 0 ? (
          <StatusView
            tone="empty"
            title="아직 그래프 데이터가 없어요"
            description="연동된 소스에서 데이터를 수집하면 그래프가 채워집니다."
          />
        ) : (
          <>
            <GraphVis
              nodes={data.nodes}
              edges={data.edges}
              selectedId={selectedId}
              onSelect={(n) => setSelectedId(n.id)}
              showLegend={false}
              showControls
              showFilters
            />
            {selectedId && (
              <NodeDetail
                node={data.nodes.find((n) => n.id === selectedId) ?? null}
                onClose={() => setSelectedId(null)}
              />
            )}
          </>
        )}
      </div>
    </div>
  );
}
