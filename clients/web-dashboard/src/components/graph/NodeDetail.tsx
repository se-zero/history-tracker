import { Icons } from "@/components/Icons";
import { markForSource } from "@/components/sources/sourceCatalog";
import { useExitPresence } from "@/hooks/useExitPresence";
import { NODE_TYPE_INFO, type GraphNode } from "@/types/graph";

interface Props {
  node: GraphNode | null;
  onClose: () => void;
  onAddToChat?: (node: GraphNode) => void;
}

export function NodeDetail({ node, onClose, onAddToChat }: Props) {
  const { shown, exiting, onExitAnimationEnd } = useExitPresence(node);
  if (!shown) return null;

  const info = NODE_TYPE_INFO[shown.type];
  // 아이콘 우선순위: actor/code는 전용 아이콘, 그 외는 소스 브랜드 로고, 카탈로그에 없으면
  // 기존 두 글자 약어(최종 폴백) — 신규 소스가 기존 GraphNodeType에 뭉뚱그려 들어가
  // node.type만으로는 브랜드를 구분할 수 없다.
  const Mark = markForSource(shown.source);
  // 노드 전환은 리마운트 없이 내용 교체(등장은 마운트 시 1회) — key를 주지 않아
  // 다른 노드를 클릭해도 같은 DOM을 유지한 채 내용만 바뀐다.
  return (
    <div
      className={exiting ? "node-detail is-exiting" : "node-detail"}
      onAnimationEnd={onExitAnimationEnd}
    >
      <div className="nd-head">
        <div className="nd-icon">
          {shown.type === "actor" ? (
            <Icons.People size={14} />
          ) : shown.type === "code" ? (
            <Icons.Code size={14} />
          ) : Mark ? (
            <Mark size={14} />
          ) : (
            info.label.slice(0, 2).toUpperCase()
          )}
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="nd-title">{shown.title}</div>
          <div className="nd-meta">{shown.meta}</div>
        </div>
        <button className="icon-btn" onClick={onClose}>
          <Icons.X />
        </button>
      </div>
      <div className="nd-snippet">{shown.snippet}</div>
      <div
        className="muted"
        style={{ fontSize: 11, fontFamily: "var(--font-mono)" }}
      >
        {shown.source}
      </div>
      {onAddToChat && (
        <div className="nd-actions">
          <button className="btn" onClick={() => onAddToChat(shown)}>
            <Icons.Plus size={13} /> 채팅에 추가
          </button>
        </div>
      )}
    </div>
  );
}
