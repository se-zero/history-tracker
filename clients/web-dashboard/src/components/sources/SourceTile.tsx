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
  return (
    <div className={"source-tile" + (error ? " oauth-error" : "")}>
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
        disabled={pending}
        aria-busy={Boolean(pending)}
      >
        <BusyLabel busy={Boolean(pending)} label="연결" busyLabel="이동 중…" />
      </button>
    </div>
  );
}
