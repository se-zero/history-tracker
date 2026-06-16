import { Icons } from "@/components/Icons";
import { NODE_TYPE_INFO, type GraphNode } from "@/types/graph";

interface Props {
  node: GraphNode | null;
  onClose: () => void;
  onAddToChat?: (node: GraphNode) => void;
}

export function NodeDetail({ node, onClose, onAddToChat }: Props) {
  if (!node) return null;
  const info = NODE_TYPE_INFO[node.type];
  return (
    <div className="node-detail">
      <div className="nd-head">
        <div className="nd-icon" style={{ background: info.cssVar }}>
          {info.label.slice(0, 2).toUpperCase()}
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
