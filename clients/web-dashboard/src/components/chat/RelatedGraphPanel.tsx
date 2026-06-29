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
  onSelectNode: (id: string | null) => void;
  onAddToChat: (node: GraphNode) => void;
  onClose: () => void;
}

// 대화 화면 우측 "관련 그래프" 패널 — 활성 답변의 서브그래프를 단독 렌더한다.
// 데이터 조회는 ChatPage가 담당하고(인용 카드와 seeds를 공유해야 하므로), 패널은 표현만 한다.
export function RelatedGraphPanel({
  data,
  isLoading,
  isError,
  onRetry,
  selectedNodeId,
  onSelectNode,
  onAddToChat,
  onClose,
}: Props) {
  // seeds(evidence가 해석된 노드)는 강조, 1홉 이웃은 흐리게. 해석된 시드가 없으면 강조하지 않는다.
  const seedIds = (data?.seeds ?? []).filter((s): s is string => s != null);
  const hasGraph = !!data && data.nodes.length > 0;
  const selectedNode = hasGraph
    ? data!.nodes.find((n) => n.id === selectedNodeId) ?? null
    : null;

  return (
    <aside className="side-panel">
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
              nodes={data!.nodes}
              edges={data!.edges}
              highlighted={seedIds.length > 0 ? seedIds : null}
              selectedId={selectedNodeId}
              onSelect={(n) => onSelectNode(n.id)}
              onBackgroundClick={() => onSelectNode(null)}
              showLegend={false}
              showFilters={false}
              showControls
              compact
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
