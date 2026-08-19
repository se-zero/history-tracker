import { BusyLabel } from "@/components/ui/BusyLabel";
import type { SourceCatalogItem } from "@/components/sources/sourceCatalog";

// "추가 가능" 구역의 균일 타일. 미지원 소스는 onConnect가 없어 버튼이 아무 동작도 하지 않는다
// (no-op) — OAuth가 아직 없는 소스에 실제 호출을 붙이지 않기 위함이다.
export function SourceTile({
  source,
  onConnect,
  pending,
  error,
}: {
  source: SourceCatalogItem;
  onConnect?: () => void;
  pending?: boolean;
  error?: boolean;
}) {
  const Mark = source.Mark;
  // onConnect가 없으면 아직 연결할 수 없는 소스다(status가 "planned") — 호버 반응을
  // 죽이고 타일 전체를 무디게 낮춰 "지원 안 함"이 조용히 보이게 한다(DESIGN.md: 경고색이 아니라
  // muted로 표현하는 상태).
  const unavailable = !onConnect;
  return (
    <div
      className={
        "source-tile" + (unavailable ? " source-tile--unavailable" : "") + (error ? " oauth-error" : "")
      }
    >
      <div className="source-logo-chip sm">
        <Mark size={18} />
      </div>
      <div className="source-tile-main">
        <div className="source-tile-name">{source.name}</div>
        <div className="source-tile-sub">{source.description}</div>
      </div>
      <button
        className="btn btn-ghost"
        onClick={onConnect}
        disabled={pending || unavailable}
        aria-busy={Boolean(pending)}
      >
        <BusyLabel busy={Boolean(pending)} label="연결" busyLabel="이동 중…" />
      </button>
    </div>
  );
}
