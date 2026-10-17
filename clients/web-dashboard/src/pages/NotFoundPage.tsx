import { useNavigate } from "react-router-dom";

import { StatusView } from "@/components/StatusView";
import { useUiLanguage } from "@/i18n/language";

export function NotFoundPage() {
  const navigate = useNavigate();
  const { lang } = useUiLanguage();
  const en = lang === "en";
  return (
    <StatusView
      tone="empty"
      title={en ? "404 — Page not found" : "404 — 페이지를 찾을 수 없습니다"}
      description={
        en
          ? "The URL may be wrong, or the resource may already be gone."
          : "잘못된 URL이거나 이미 삭제된 리소스일 수 있어요."
      }
      action={
        <button
          className="btn btn-primary"
          onClick={() => navigate("/", { replace: true })}
        >
          {en ? "Back home" : "홈으로 돌아가기"}
        </button>
      }
      fullPage
    />
  );
}
