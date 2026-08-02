import { useEffect, useState } from "react";

import { InlineError } from "@/components/ui/InlineError";
import { useDisconnectIntegration } from "@/hooks/useIntegrations";
import type { IntegrationProvider } from "@/api/integrations";

// provider별로 "무엇이 지워지는지"를 구체적으로 말한다 — "데이터가 삭제됩니다" 같은 뭉뚱그린
// 문구는 사용자가 무엇을 잃는지 판단할 수 없어 파괴적 동작의 고지로 부족하다.
const DELETED_DATA: Record<IntegrationProvider, string> = {
  github: "수집한 커밋·Pull Request·이슈와 그 그래프",
  slack: "수집한 채널 메시지·스레드와 그 그래프",
  jira: "수집한 이슈와 그 그래프",
};

const LABEL: Record<IntegrationProvider, string> = {
  github: "GitHub",
  slack: "Slack",
  jira: "Jira",
};

/**
 * 연동 해제 버튼 + 사전 경고 다이얼로그.
 *
 * 해제는 자격증명뿐 아니라 그 소스에서 수집한 그래프까지 서버에서 지운다(되돌릴 수 없음).
 * 그래서 버튼을 누르는 즉시 실행하지 않고, 지워지는 것·남는 것·재연결 시 동작을 먼저 보여준다.
 */
export function DisconnectIntegration({
  projectId,
  provider,
}: {
  projectId: string;
  provider: IntegrationProvider;
}) {
  const [confirming, setConfirming] = useState(false);
  const disconnect = useDisconnectIntegration(projectId);

  // 다이얼로그를 닫았다 다시 열면 지난 실패 문구가 남아 있지 않게 한다.
  useEffect(() => {
    if (confirming) disconnect.reset();
    // disconnect는 매 렌더 새 객체라 의존성에 넣으면 무한 루프가 된다 — 열림 상태만 본다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [confirming]);

  const close = () => {
    if (disconnect.isPending) return; // 요청 중 닫으면 결과를 알릴 곳이 사라진다
    setConfirming(false);
  };

  return (
    <>
      {/* 상태 정보(배지·수집시각)와 액션을 시각적으로 가르는 세로 헤어라인 */}
      <span className="src-divider" aria-hidden />
      <button
        type="button"
        className="btn btn-ghost src-disconnect"
        onClick={() => setConfirming(true)}
      >
        연동 해제
      </button>

      {confirming && (
        <div className="confirm-overlay" onMouseDown={close}>
          <div
            className="confirm-dialog"
            role="dialog"
            aria-modal="true"
            aria-label={`${LABEL[provider]} 연동 해제`}
            onMouseDown={(e) => e.stopPropagation()}
            onKeyDown={(e) => {
              if (e.key === "Escape") close();
            }}
          >
            <h4 className="confirm-title">{LABEL[provider]} 연동을 해제할까요?</h4>
            <ul className="confirm-points">
              <li>
                <span className="confirm-mark danger" aria-hidden />
                저장된 접근 권한과 {DELETED_DATA[provider]}가 삭제됩니다.
              </li>
              <li>
                <span className="confirm-mark" aria-hidden />
                다른 연동과 대화 기록은 그대로 유지됩니다.
              </li>
              <li>
                <span className="confirm-mark" aria-hidden />
                다시 연결하면 처음부터 새로 수집합니다 — 삭제된 데이터는 복구되지 않습니다.
              </li>
            </ul>

            {disconnect.isError && (
              <InlineError style={{ marginTop: 12 }}>
                해제하지 못했어요. 잠시 후 다시 시도해 주세요.
              </InlineError>
            )}

            <div className="confirm-actions">
              <button
                type="button"
                className="btn btn-ghost"
                onClick={close}
                disabled={disconnect.isPending}
              >
                취소
              </button>
              <button
                type="button"
                className="btn btn-danger"
                autoFocus
                onClick={() =>
                  disconnect.mutate(provider, { onSuccess: () => setConfirming(false) })
                }
                disabled={disconnect.isPending}
              >
                {disconnect.isPending ? "해제하는 중…" : "해제하고 삭제"}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
