import { useNavigate } from "react-router-dom";

import { StatusView } from "@/components/StatusView";

export function NotFoundPage() {
  const navigate = useNavigate();
  return (
    <StatusView
      tone="empty"
      title="404 — 페이지를 찾을 수 없습니다"
      description="잘못된 URL이거나 이미 삭제된 리소스일 수 있어요."
      action={
        <button
          className="btn btn-primary"
          onClick={() => navigate("/", { replace: true })}
        >
          홈으로 돌아가기
        </button>
      }
      fullPage
    />
  );
}
