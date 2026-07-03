import { useEffect, useState } from "react";
import { useLocation } from "react-router-dom";

import { GraphVis } from "@/components/graph/GraphVis";
import { NodeDetail } from "@/components/graph/NodeDetail";
import { StatusView } from "@/components/StatusView";
import { Topbar } from "@/components/shell/Topbar";
import { useGraph, useGraphBuildStatus, useRebuildGraph } from "@/hooks/useGraph";
import type { Project } from "@/types/api";
import type { GraphNode } from "@/types/graph";

export function GraphPage({ project }: { project: Project }) {
  const [selectedId, setSelectedId] = useState<string | null>(null);

  // 통합 검색에서 노드를 골라 넘어온 경우 — 검색은 그래프 전체를 뒤지므로
  // overview(최근 top-N)에 없는 노드일 수 있어, 상세 표시용 노드 데이터를 state로 받는다.
  const location = useLocation();
  const searchNode =
    (location.state as { searchNode?: GraphNode } | null)?.searchNode ?? null;
  useEffect(() => {
    if (searchNode) setSelectedId(searchNode.id);
  }, [searchNode]);

  const graphQuery = useGraph(project.id);
  // 페이지는 프로젝트의 현재 빌드 상태를 그대로 반영한다(개인 프로젝트라 빌드 주인은 항상 본인).
  // 진입 시 상태를 조회하고 running이면 폴링·진행표시가 재개된다(다른 탭에서 떠나 있어도).
  const buildStatus = useGraphBuildStatus(project.id);

  // 트리거(202)는 즉시 반환되고, 완료는 buildStatus 폴링으로 확인한다.
  // verify=false: 방안 A(임베딩, 빠름) / verify=true: 방안 D(LLM 검증, 느림·비용).
  const rebuild = useRebuildGraph(project.id);

  const data = graphQuery.data;
  const status = buildStatus.data;

  const building = rebuild.isPending || status?.state === "running";
  // 진행 중 라벨 구분: 폴링 중이면 status.verify, 트리거 직후 짧은 구간엔 mutate variables.
  const buildingVerify =
    status?.state === "running" ? status.verify : rebuild.variables;

  const succeeded = status?.state === "succeeded" ? status.result : null;
  const builtEdges = succeeded
    ? succeeded.triggered_by + succeeded.discussed_in + succeeded.reference + succeeded.thread_propagated
    : 0;
  const failed = rebuild.isError || status?.state === "failed";

  return (
    <div
      style={{
        flex: 1,
        position: "relative",
        display: "flex",
        flexDirection: "column",
      }}
    >
      <Topbar
        crumbs={[project.name, "그래프 탐색"]}
        right={
          <>
            {data && (
              <span className="muted" style={{ fontSize: 12 }}>
                {data.nodes.length} 노드 · {data.edges.length} 연결
              </span>
            )}
            {succeeded && (
              <span className="muted" style={{ fontSize: 12 }}>
                ✓ 연결 {builtEdges}개 생성
              </span>
            )}
            {failed && (
              <span style={{ fontSize: 12, color: "#e5484d" }}>재구축 실패</span>
            )}
            <button
              className="btn"
              style={{ marginLeft: 12 }}
              onClick={() => rebuild.mutate(false)}
              disabled={building}
              title="수집된 데이터로 소스 간 연결을 임베딩 유사도로 다시 계산합니다 (빠름)"
            >
              {building && buildingVerify === false
                ? "재구축 중…"
                : "그래프 재구축"}
            </button>
            <button
              className="btn"
              style={{ marginLeft: 8 }}
              onClick={() => {
                if (
                  window.confirm(
                    "LLM 검증으로 정밀 재구축할까요?\n기존 시맨틱 연결을 비우고 LLM이 후보를 검증해 다시 만듭니다. 시간과 비용이 더 듭니다.",
                  )
                ) {
                  rebuild.mutate(true);
                }
              }}
              disabled={building}
              title="LLM이 후보를 검증해 잘못된 연결을 거릅니다 (느림·LLM 비용)"
            >
              {building && buildingVerify === true
                ? "정밀 재구축 중…"
                : "정밀 재구축 (LLM)"}
            </button>
          </>
        }
      />
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
                node={
                  data.nodes.find((n) => n.id === selectedId) ??
                  (searchNode?.id === selectedId ? searchNode : null)
                }
                onClose={() => setSelectedId(null)}
              />
            )}
          </>
        )}
      </div>
    </div>
  );
}
