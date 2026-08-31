import { Icons } from "@/components/Icons";
import { BusyLabel } from "@/components/ui/BusyLabel";
import { InlineError } from "@/components/ui/InlineError";
import type { SourceCatalogItem } from "@/components/sources/sourceCatalog";

// secondaryConnect를 가진 wired 소스만 이 다이얼로그를 열 수 있다 — 호출부(SourceTileGrid)가
// hasTokenConnect로 걸러 준 뒤 넘기므로, 여기서는 그 보장을 타입으로 좁혀 문구 필드를 옵셔널
// 체이닝 없이 바로 읽는다.
type WiredSource = Extract<SourceCatalogItem, { status: "wired" }>;
export type ConnectMethodSource = WiredSource & {
  secondaryConnect: NonNullable<WiredSource["secondaryConnect"]>;
};

/**
 * 연결 방식이 둘인 소스(OAuth 앱 vs 토큰 붙여넣기)를 고르는 다이얼로그.
 *
 * 타일 "연결" 클릭 시 바로 authorize로 보내지 않고 먼저 방식을 고르게 한다. 선택지 문구는
 * source.secondaryConnect(카탈로그)가 소유하므로 여기는 provider id를 비교하지 않는다.
 */
export function ConnectMethodDialog({
  source,
  oauthPending,
  oauthError,
  onOauth,
  onToken,
  onClose,
}: {
  source: ConnectMethodSource;
  oauthPending: boolean;
  oauthError?: boolean;
  onOauth: () => void;
  onToken: () => void;
  onClose: () => void;
}) {
  const Mark = source.Mark;
  const titleId = `connect-method-title-${source.id}`;

  const close = () => {
    if (oauthPending) return; // 리다이렉트 이동 중 닫으면 결과를 알릴 곳이 사라진다
    onClose();
  };

  return (
    <div className="confirm-overlay" onMouseDown={close}>
      <div
        className="confirm-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onMouseDown={(e) => e.stopPropagation()}
        onKeyDown={(e) => {
          if (e.key === "Escape") close();
        }}
      >
        <h4 id={titleId} className="confirm-title">
          {source.name} 연결
        </h4>

        {/* 포커스를 다이얼로그 안으로 옮겨야 위 onKeyDown이 Escape를 받는다 — DisconnectIntegration의
            autoFocus와 같은 이유. 첫 선택지가 자연스러운 시작 지점이다. */}
        <button type="button" className="connect-choice" onClick={onOauth} disabled={oauthPending} autoFocus>
          <span className="connect-choice-icon">
            <Mark size={18} />
          </span>
          <span className="connect-choice-body">
            <span className="connect-choice-title">
              <BusyLabel busy={oauthPending} label={`${source.name} 앱으로 연결`} busyLabel="이동 중…" />
            </span>
            <span className="connect-choice-desc">{source.secondaryConnect.oauthHint}</span>
          </span>
        </button>

        <button type="button" className="connect-choice" onClick={onToken} disabled={oauthPending}>
          <span className="connect-choice-icon">
            <Icons.Plug size={18} />
          </span>
          <span className="connect-choice-body">
            <span className="connect-choice-title">토큰으로 연결</span>
            <span className="connect-choice-desc">{source.secondaryConnect.tokenHint}</span>
          </span>
        </button>

        {/* authorize 실패는 그리드의 InlineError가 오버레이 뒤에 가려지므로 다이얼로그 안에서도 알린다 */}
        {oauthError && (
          <InlineError style={{ marginTop: 12 }}>
            연결에 실패했어요. 잠시 후 다시 시도해 주세요.
          </InlineError>
        )}

        <div className="confirm-actions">
          <button type="button" className="btn btn-ghost" onClick={close} disabled={oauthPending}>
            취소
          </button>
        </div>
      </div>
    </div>
  );
}
