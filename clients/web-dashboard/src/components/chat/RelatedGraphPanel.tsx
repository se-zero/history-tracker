import { Icons } from "@/components/Icons";
import { InlineError } from "@/components/ui/InlineError";
import { GraphVis } from "@/components/graph/GraphVis";
import { NodeDetail } from "@/components/graph/NodeDetail";
import type { GraphNode, SubgraphData } from "@/types/graph";

interface Props {
  data: SubgraphData | undefined;
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
  selectedNodeId: string | null;
  // 인용 카드 hover로 강조할 노드 (선택과 별개 — NodeDetail은 열지 않고 시각 강조만).
  emphasizedId: string | null;
  onSelectNode: (id: string | null) => void;
  onHoverNode: (id: string | null) => void;
  onAddToChat: (node: GraphNode) => void;
  onResizeStart: (e: React.PointerEvent) => void;
  onClose: () => void;
  // 패널이 열린 상태에서 새 답변이 도착했을 때만 true — GraphVis에 그대로 관통한다.
  ignite?: boolean;
  onIgniteConsumed?: () => void;
  // 활성 답변 id — GraphVis 재마운트 key. 서브그래프가 캐시 히트(staleTime: Infinity)면
  // 로딩 구간 없이 답변이 전환되어 인스턴스가 유지되는데, 그러면 점등을 이미 재생한
  // 래치(igniting)가 살아남아 과거 답변 그래프에서 점등이 재재생된다. 답변 단위
  // 재마운트를 key로 강제해 "1회 재생" 전제를 구조로 보장한다.
  activeMessageId?: string | null;
}

// 대화 화면 우측 "관련 그래프" 패널 — 활성 답변의 서브그래프를 단독 렌더한다.
// 데이터 조회는 ChatPage가 담당하고(인용 카드와 seeds를 공유해야 하므로), 패널은 표현만 한다.
export function RelatedGraphPanel({
  data,
  isLoading,
  isError,
  onRetry,
  selectedNodeId,
  emphasizedId,
  onSelectNode,
  onHoverNode,
  onAddToChat,
  onResizeStart,
  onClose,
  ignite,
  onIgniteConsumed,
  activeMessageId,
}: Props) {
  // seeds(evidence가 해석된 노드)는 강조, 1홉 이웃은 흐리게. 해석된 시드가 없으면 강조하지 않는다.
  const seedIds = (data?.seeds ?? []).filter((s): s is string => s != null);
  const hasGraph = !!data && data.nodes.length > 0;
  const selectedNode = hasGraph
    ? data!.nodes.find((n) => n.id === selectedNodeId) ?? null
    : null;

  return (
    <aside className="side-panel">
      <div
        className="panel-resizer"
        onPointerDown={onResizeStart}
        role="separator"
        aria-orientation="vertical"
        title="너비 조절"
      />
      <div className="side-panel-head">
        <span>관련 그래프</span>
        <button className="icon-btn" onClick={onClose} title="패널 닫기">
          <Icons.X />
        </button>
      </div>
      <div className="side-panel-body">
        {isLoading ? (
          <div className="side-panel-loading">
            <span className="spinner" />
          </div>
        ) : isError ? (
          <div className="side-panel-empty">
            <InlineError style={{ marginBottom: 10 }}>
              관련 그래프를 불러오지 못했어요.
            </InlineError>
            <button className="btn" onClick={onRetry}>
              다시 시도
            </button>
          </div>
        ) : hasGraph ? (
          <>
            <GraphVis
              key={activeMessageId ?? "none"}
              nodes={data!.nodes}
              edges={data!.edges}
              highlighted={seedIds.length > 0 ? seedIds : null}
              selectedId={selectedNodeId}
              emphasizedId={emphasizedId}
              onSelect={(n) => onSelectNode(n.id === selectedNodeId ? null : n.id)}
              onHoverNode={onHoverNode}
              onBackgroundClick={() => onSelectNode(null)}
              showLegend={false}
              showFilters={false}
              showControls
              showFit={false}
              compact
              ignite={ignite}
              onIgniteConsumed={onIgniteConsumed}
            />
            {selectedNode && (
              <NodeDetail
                node={selectedNode}
                onClose={() => onSelectNode(null)}
                onAddToChat={onAddToChat}
              />
            )}
          </>
        ) : (
          <div className="side-panel-empty">
            이 답변과 연결된 그래프가 없어요.
          </div>
        )}
      </div>
    </aside>
  );
}
