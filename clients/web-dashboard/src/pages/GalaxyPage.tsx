import { useMemo, useState } from "react";

import { ConstellationVis } from "@/components/graph/ConstellationVis";
import { NodeDetail } from "@/components/graph/NodeDetail";
import { StatusView } from "@/components/StatusView";
import { Topbar } from "@/components/shell/Topbar";
import { useConstellation } from "@/hooks/useGraph";
import type { Project } from "@/types/api";

/**
 * 작업 성좌 뷰 — Issue/PR을 별성으로 삼아 그래프를 은하처럼 보여주는 페이지.
 * 정밀 탐색용 2D 뷰(그래프 탐색)와 별개로, 전체 구조를 한눈에 보여주는 용도다.
 */
export function GalaxyPage({ project }: { project: Project }) {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const graphQuery = useConstellation(project.id);
  const data = graphQuery.data;

  // 별성 개수 = 서버가 알려준 작업 단위 수.
  const starCount = data?.workUnitIds.length ?? 0;

  const selected = useMemo(
    () => data?.nodes.find((n) => n.id === selectedId) ?? null,
    [data, selectedId],
  );

  return (
    <div className="galaxy-page">
      <Topbar
        crumbs={[project.name, "작업 성좌"]}
        right={
          data ? (
            <span className="muted" style={{ fontSize: 12 }}>
              성좌 {starCount} · 노드 {data.nodes.length} · 연결 {data.edges.length}
            </span>
          ) : null
        }
      />
      <div className="galaxy-stage">
        {graphQuery.isLoading ? (
          <StatusView tone="loading" description="성좌를 그리는 중…" />
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
            description="연동된 소스에서 데이터를 수집하면 성좌가 만들어집니다."
          />
        ) : (
          <>
            <ConstellationVis
              nodes={data.nodes}
              edges={data.edges}
              workUnitIds={data.workUnitIds}
              selectedId={selectedId}
              onSelect={(n) => setSelectedId(n.id)}
              onBackgroundClick={() => setSelectedId(null)}
            />
            {selected && (
              <NodeDetail node={selected} onClose={() => setSelectedId(null)} />
            )}
          </>
        )}
      </div>
    </div>
  );
}
