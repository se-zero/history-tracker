import { Icons } from "@/components/Icons";
import { markForSource } from "@/components/sources/sourceCatalog";
import { NODE_TYPE_INFO, type GraphNode } from "@/types/graph";

interface Props {
  node: GraphNode | null;
  onClose: () => void;
  onAddToChat?: (node: GraphNode) => void;
}

export function NodeDetail({ node, onClose, onAddToChat }: Props) {
  if (!node) return null;
  const info = NODE_TYPE_INFO[node.type];
  // 아이콘 우선순위: actor/code는 전용 아이콘, 그 외는 소스 브랜드 로고, 카탈로그에 없으면
  // 기존 두 글자 약어(최종 폴백) — 신규 소스가 기존 GraphNodeType에 뭉뚱그려 들어가
  // node.type만으로는 브랜드를 구분할 수 없다.
  const Mark = markForSource(node.source);
  return (
    <div className="node-detail">
      <div className="nd-head">
        <div className="nd-icon">
          {node.type === "actor" ? (
            <Icons.People size={14} />
          ) : node.type === "code" ? (
            <Icons.Code size={14} />
          ) : Mark ? (
            <Mark size={14} />
          ) : (
            info.label.slice(0, 2).toUpperCase()
          )}
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="nd-title">{node.title}</div>
          <div className="nd-meta">{node.meta}</div>
        </div>
        <button className="icon-btn" onClick={onClose}>
          <Icons.X />
        </button>
      </div>
      <div className="nd-snippet">{node.snippet}</div>
      <div
        className="muted"
        style={{ fontSize: 11, fontFamily: "var(--font-mono)" }}
      >
        {node.source}
      </div>
      {onAddToChat && (
        <div className="nd-actions">
          <button className="btn" onClick={() => onAddToChat(node)}>
            <Icons.Plus size={13} /> 채팅에 추가
          </button>
        </div>
      )}
    </div>
  );
}
