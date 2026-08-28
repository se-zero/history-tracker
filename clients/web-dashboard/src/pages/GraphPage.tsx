import { useEffect, useMemo, useState } from "react";
import { useLocation } from "react-router-dom";

import { WorkUnitCanvas } from "@/components/graph/WorkUnitCanvas";
import { NodeDetail } from "@/components/graph/NodeDetail";
import { StatusView } from "@/components/StatusView";
import { Topbar } from "@/components/shell/Topbar";
import { useAuth } from "@/auth/AuthProvider";
import {
  useWorkUnits,
  useGraphBuildStatus,
  useRebuildGraph,
  useWorkUnitNeighborhoods,
} from "@/hooks/useGraph";
import type { Project } from "@/types/api";
import type { GraphNode } from "@/types/graph";

/**
 * 그래프 확인 화면 — Issue/PR을 작업 단위로 삼아 그 주변 노드를 묶어 보여주는 페이지.
 * 프로젝트 그래프를 보는 유일한 화면이라, 그래프 재구축 트리거도 여기서 한다.
 */
export function GraphPage({ project }: { project: Project }) {
  const { user } = useAuth();
  // 무료 티어는 정밀 재구축(LLM 검증) 불가 — docs/public-readiness.md 1층 참고.
  const preciseRebuildLocked = user?.plan === "FREE";
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const graphQuery = useWorkUnits(project.id);
  const base = graphQuery.data;

  // 통합 검색에서 노드를 골라 넘어온 경우 — 검색은 그래프 전체를 뒤지므로
  // 작업 단위 조회(작업 단위 + 최신 구성 노드)에 없는 노드일 수 있어, 상세 표시용 노드를 state로 받는다.
  const location = useLocation();
  const searchNode =
    (location.state as { searchNode?: GraphNode } | null)?.searchNode ?? null;
  useEffect(() => {
    if (searchNode) setSelectedId(searchNode.id);
  }, [searchNode]);

  // 묶음을 열면 그 작업 단위의 이웃을 추가로 불러와 합친다 —
  // 기본 조회는 구성 노드를 최신 N개로 자르므로 오래된 작업은 작업 단위만 있고 구성 노드가 비어 있다.
  const [expandedIds, setExpandedIds] = useState<string[]>([]);
  const expanded = useWorkUnitNeighborhoods(project.id, expandedIds);
  useEffect(() => {
    setExpandedIds([]);
  }, [project.id]);

  // 기존 노드를 앞에 두고 새 것만 덧붙인다 — 작업 단위 순서가 바뀌면 열려 있던 묶음이 어긋난다.
  const data = useMemo(() => {
    if (!base) return undefined;
    if (expanded.nodes.length === 0) return base;

    const seenNodes = new Set(base.nodes.map((n) => n.id));
    const nodes = [...base.nodes];
    for (const node of expanded.nodes) {
      if (seenNodes.has(node.id)) continue;
      seenNodes.add(node.id);
      nodes.push(node);
    }

    const edgeKey = (edge: readonly [string, string]) => `${edge[0]} ${edge[1]}`;
    const seenEdges = new Set(base.edges.map(edgeKey));
    const edges = [...base.edges];
    for (const edge of expanded.edges) {
      const key = edgeKey(edge);
      if (seenEdges.has(key)) continue;
      seenEdges.add(key);
      edges.push(edge);
    }

    return { nodes, edges, workUnitIds: base.workUnitIds };
  }, [base, expanded.nodes, expanded.edges]);

  const selected = useMemo(
    () =>
      data?.nodes.find((n) => n.id === selectedId) ??
      (searchNode?.id === selectedId ? searchNode : null),
    [data, selectedId, searchNode],
  );

  // 페이지는 프로젝트의 현재 빌드 상태를 그대로 반영한다(개인 프로젝트라 빌드 주인은 항상 본인).
  // 진입 시 상태를 조회하고 running이면 폴링·진행표시가 재개된다(다른 탭에서 떠나 있어도).
  const buildStatus = useGraphBuildStatus(project.id);

  // 트리거(202)는 즉시 반환되고, 완료는 buildStatus 폴링으로 확인한다.
  // verify=false: 임베딩만 사용(빠름) / verify=true: 수동 정밀 구축(LLM 검수, 느림·비용).
  const rebuild = useRebuildGraph(project.id);

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
    <div className="wu-page">
      <Topbar
        crumbs={[project.name, "그래프 확인"]}
        right={
          <>
            {data && (
              <span className="muted" style={{ fontSize: 12 }}>
                노드 {data.nodes.length} · 연결 {data.edges.length}
              </span>
            )}
            {succeeded && (
              <span className="muted" style={{ fontSize: 12 }}>
                ✓ 연결 {builtEdges}개 생성
              </span>
            )}
            {failed && (
              <span style={{ fontSize: 12, color: "var(--danger)" }}>
                재구축 실패
              </span>
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
              disabled={building || preciseRebuildLocked}
              title={
                preciseRebuildLocked
                  ? "무료 플랜에서는 사용할 수 없습니다 (유료 전환 필요)"
                  : "LLM이 후보를 검증해 잘못된 연결을 거릅니다 (느림·LLM 비용)"
              }
            >
              {building && buildingVerify === true
                ? "정밀 재구축 중…"
                : "정밀 재구축 (LLM)"}
            </button>
          </>
        }
      />
      <div className="wu-stage">
        {graphQuery.isLoading ? (
          <StatusView tone="loading" description="그래프를 그리는 중…" />
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
            description="연동된 소스에서 데이터를 수집하면 그래프가 만들어집니다."
          />
        ) : (
          <>
            <WorkUnitCanvas
              nodes={data.nodes}
              edges={data.edges}
              workUnitIds={data.workUnitIds}
              onExpandWorkUnit={(nodeId) =>
                setExpandedIds((prev) =>
                  prev.includes(nodeId) ? prev : [...prev, nodeId],
                )
              }
              expanding={expanded.isFetching}
              selectedId={selectedId}
              onSelect={(n) => setSelectedId(n.id)}
              onBackgroundClick={() => setSelectedId(null)}
            />
            <NodeDetail node={selected} onClose={() => setSelectedId(null)} />
          </>
        )}
      </div>
    </div>
  );
}
