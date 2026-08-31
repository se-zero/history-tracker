import { useState } from "react";

import { InlineError } from "@/components/ui/InlineError";
import { ConnectMethodDialog, type ConnectMethodSource } from "@/components/sources/ConnectMethodDialog";
import { SlackTokenConnectDialog } from "@/components/sources/SlackTokenConnectDialog";
import { SourceTile } from "@/components/sources/SourceTile";
import {
  hasTokenConnect,
  isOAuthConnectable,
  sourceCatalog,
  sourceName,
  type SourceCatalogItem,
} from "@/components/sources/sourceCatalog";
import { useIntegrationAuthorize } from "@/hooks/useIntegrationOAuth";

// hasTokenConnect의 boolean 판정을 타입 좁히기로도 쓰기 위한 얇은 래퍼 — sourceCatalog는
// ConnectMethodDialog를 모르므로(카탈로그가 UI 타입을 알 필요가 없다) 좁히기는 여기서만 한다.
function hasSecondaryConnect(source: SourceCatalogItem): source is ConnectMethodSource {
  return hasTokenConnect(source);
}

// "추가 가능" 타일 그리드. GitHub는 항상 행이라 제외하고, 이미 연동 중(행)인 소스도 제외한다 —
// 렌더는 카탈로그 배열의 filter+map으로만 하고, 연결 버튼 배선 여부는 카탈로그의 connectable이
// 정한다(공용 코드에 provider 분기를 두지 않는다). authorize 에러는 타일 균일성을 지키기 위해
// 타일 안이 아니라 그리드 바로 아래 전폭 InlineError로 보여준다.
// 토큰 폼 에러는 타일에 넣지 않고 다이얼로그 안에 둔다 — 한 타일만 폼으로 커지지 않게.
export function SourceTileGrid({
  projectId,
  linkedProviders,
  oauthError,
}: {
  projectId: string;
  linkedProviders: string[];
  oauthError?: { provider: string | null; message: string };
}) {
  const authorize = useIntegrationAuthorize(projectId);
  const [tokenDialogOpen, setTokenDialogOpen] = useState(false);
  // 연결 방식이 둘인 소스는 "연결" 클릭 시 authorize를 바로 부르지 않고 이 다이얼로그로 먼저
  // 방식을 고르게 한다.
  const [methodDialogSource, setMethodDialogSource] = useState<ConnectMethodSource | null>(null);

  const tileSources = sourceCatalog.filter(
    (source) => source.id !== "github" && !linkedProviders.includes(source.id),
  );

  const oauthPendingFor = (source: (typeof tileSources)[number]) =>
    isOAuthConnectable(source) &&
    authorize.variables === source.id &&
    (authorize.isPending || authorize.isSuccess);

  return (
    <>
      <div className="source-tiles">
        {tileSources.map((source) => (
          <SourceTile
            key={source.id}
            source={source}
            onConnect={
              isOAuthConnectable(source)
                ? hasSecondaryConnect(source)
                  ? () => {
                      // 이전 시도의 실패 기록이 새로 연 다이얼로그에 미리 뜨지 않게 초기화 —
                      // SlackTokenConnectDialog의 마운트 시 reset과 같은 이유.
                      authorize.reset();
                      setMethodDialogSource(source);
                    }
                  : () => authorize.mutate(source.id)
                : undefined
            }
            pending={oauthPendingFor(source)}
            error={oauthError?.provider === source.id}
          />
        ))}
      </div>

      {authorize.isError && (
        <InlineError style={{ marginTop: 12 }}>
          {sourceName(authorize.variables)} 연결에 실패했어요. 잠시 후 다시 시도해 주세요.
        </InlineError>
      )}
      {oauthError && <InlineError style={{ marginTop: 12 }}>{oauthError.message}</InlineError>}

      {methodDialogSource && (
        <ConnectMethodDialog
          source={methodDialogSource}
          oauthPending={oauthPendingFor(methodDialogSource)}
          oauthError={authorize.isError && authorize.variables === methodDialogSource.id}
          onOauth={() => authorize.mutate(methodDialogSource.id)}
          onToken={() => {
            setMethodDialogSource(null);
            setTokenDialogOpen(true);
          }}
          onClose={() => setMethodDialogSource(null)}
        />
      )}

      {tokenDialogOpen && (
        <SlackTokenConnectDialog projectId={projectId} onClose={() => setTokenDialogOpen(false)} />
      )}
    </>
  );
}
