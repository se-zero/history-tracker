import { useState } from "react";

import { Icons } from "@/components/Icons";
import { PRIVACY_POLICY_EFFECTIVE_DATE } from "@/components/landing/LegalLayout";
import { PATHS } from "@/routes";

// 약관 배너와 같은 이유의 별도 키. 약관 시행일 저장값과 섞으면 한쪽을 닫았을 때 다른 공지까지 사라진다.
const DISMISS_KEY = "ht.privacyNotice.dismissed";

function todayLocalDateString(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

function formatMonthDay(isoDate: string): string {
  const match = isoDate.match(/^\d{4}-(\d{2})-(\d{2})$/);
  if (!match) return isoDate;
  return `${Number(match[1])}월 ${Number(match[2])}일`;
}

function readDismissed(): string | null {
  try {
    return window.localStorage.getItem(DISMISS_KEY);
  } catch {
    return null;
  }
}

function writeDismissed(value: string) {
  try {
    window.localStorage.setItem(DISMISS_KEY, value);
  } catch {
    // 저장 실패해도 이번 세션 표시는 유지된다.
  }
}

// 개인정보처리방침 변경 공지. 방침 제11조가 시행 7일 전 서비스 내 공지를 요구한다.
// 시행일이 지나면 조건이 꺼지므로 배너를 내리는 배포가 따로 필요 없다.
export function PrivacyNoticeBanner() {
  const [dismissed, setDismissed] = useState<string | null>(readDismissed);

  const isBeforeEffective = todayLocalDateString() < PRIVACY_POLICY_EFFECTIVE_DATE;
  if (!isBeforeEffective || dismissed === PRIVACY_POLICY_EFFECTIVE_DATE) return null;

  const handleDismiss = () => {
    writeDismissed(PRIVACY_POLICY_EFFECTIVE_DATE);
    setDismissed(PRIVACY_POLICY_EFFECTIVE_DATE);
  };

  return (
    <div className="terms-notice" role="note">
      <div className="terms-notice-body">
        <strong className="terms-notice-title">
          개인정보처리방침이 개정됩니다 · {formatMonthDay(PRIVACY_POLICY_EFFECTIVE_DATE)} 시행
        </strong>
        <p className="terms-notice-text">
          개인정보 보호책임자 표시, 탈퇴 후 결제 기록 보관, 결제 안내 메일 발송 위탁(Resend),
          외부 앱(코딩 에이전트) 연결 정보, 브라우저에 저장되는 항목이 추가됩니다.{" "}
          <a href={PATHS.privacy} target="_blank" rel="noopener noreferrer">
            개인정보처리방침
          </a>
        </p>
      </div>
      <button
        type="button"
        className="icon-btn terms-notice-dismiss"
        onClick={handleDismiss}
        aria-label="개인정보 공지 닫기"
      >
        <Icons.X size={14} />
      </button>
    </div>
  );
}
