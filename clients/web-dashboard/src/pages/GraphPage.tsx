import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { GraphVis } from "@/components/graph/GraphVis";
import { NodeDetail } from "@/components/graph/NodeDetail";
import { StatusView } from "@/components/StatusView";
import { getProjectGraph, rebuildProjectGraph } from "@/api/graph";
import type { Project } from "@/types/api";

export function GraphPage({ project }: { project: Project }) {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const queryClient = useQueryClient();

  const graphQuery = useQuery({
    queryKey: ["graph", project.id],
    queryFn: () => getProjectGraph(project.id),
  });

  // 재구축 완료 후 그래프를 다시 불러와 새로 생긴 연결을 반영한다.
  const rebuild = useMutation({
    mutationFn: () => rebuildProjectGraph(project.id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["graph", project.id] });
    },
  });

  const data = graphQuery.data;
  const built = rebuild.data;
  const builtEdges = built
    ? built.triggered_by + built.discussed_in + built.reference + built.thread_propagated
    : 0;

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
        {rebuild.isSuccess && (
          <span className="muted" style={{ fontSize: 12 }}>
            ✓ 연결 {builtEdges}개 생성
          </span>
        )}
        {rebuild.isError && (
          <span style={{ fontSize: 12, color: "#e5484d" }}>재구축 실패</span>
        )}
        <button
          className="btn"
          style={{ marginLeft: 12 }}
          onClick={() => rebuild.mutate()}
          disabled={rebuild.isPending}
          title="수집된 데이터로 소스 간 연결(Jira·GitHub·Slack)을 다시 계산합니다"
        >
          {rebuild.isPending ? "재구축 중…" : "그래프 재구축"}
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
              onBackgroundClick={() => setSelectedId(null)}
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
