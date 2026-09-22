import { useState } from "react";

import { Icons } from "@/components/Icons";
import { LEGAL_CONTACT_EMAIL, TERMS_EFFECTIVE_DATE } from "@/components/landing/LegalLayout";
import { PATHS } from "@/routes";

// 닫은 상태를 기억하는 localStorage 키 — 값은 시행일 문자열이다. 약관이 다시 개정돼
// TERMS_EFFECTIVE_DATE가 바뀌면 저장된 값과 달라지므로 새 공지가 다시 뜬다(같은 배너
// 컴포넌트를 재사용해도 "이번 개정을 봤는지"가 시행일 단위로 갈린다).
const DISMISS_KEY = "ht.termsNotice.dismissed";

// 기기 로컬 날짜를 "YYYY-MM-DD"로 조립한다. Date.toISOString()은 UTC로 변환하므로 자정 근처
// (예: 한국 오전 0~9시는 UTC로 전날)에 시행일 하루 전인데도 이미 지난 것처럼 잘못 판정할 수
// 있다 — 로컬 캘린더 필드(getFullYear/getMonth/getDate)를 직접 읽어 그 왜곡을 피한다.
function todayLocalDateString(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

// "YYYY-MM-DD" → "10월 1일". 위와 같은 이유로 Date로 다시 파싱하지 않고 문자열을 직접 쪼갠다.
function formatMonthDay(isoDate: string): string {
  const match = isoDate.match(/^\d{4}-(\d{2})-(\d{2})$/);
  if (!match) return isoDate;
  return `${Number(match[1])}월 ${Number(match[2])}일`;
}

function readDismissed(): string | null {
  try {
    return window.localStorage.getItem(DISMISS_KEY);
  } catch {
    // 프라이버시 모드 등에서 접근이 throw할 수 있다 — 못 읽으면 그냥 다시 보인다.
    return null;
  }
}

function writeDismissed(value: string) {
  try {
    window.localStorage.setItem(DISMISS_KEY, value);
  } catch {
    // 저장 실패해도 이번 세션 표시는 정상 동작하므로 무시한다.
  }
}

// 이용약관 개정 공지 배너 — AppShell의 `.main` 맨 위, Topbar보다 위에 둔다(그래프 화면은
// 자체 Topbar를 그리므로 이 배너가 그 위에 온다).
//
// 기존 이용자는 재동의를 받지 않는다(제12조 ② "공지 후 계속 이용하면 동의로 본다") — 이
// 배너가 그 "서비스 내 공지"를 이행한다. 시행일(TERMS_EFFECTIVE_DATE)이 지나면 표시 조건
// 자체가 꺼지므로 배너를 내리는 별도 배포가 필요 없다.
//
// 색은 DESIGN.md "warning이 없는 이유"를 따른다 — 결과 신뢰도 고지와 같은 급의 정보 안내라
// 앰버 없이 무채색 헤어라인 상자로 둔다(.composer-notice와 같은 계열).
export function TermsNoticeBanner() {
  const [dismissed, setDismissed] = useState<string | null>(readDismissed);

  const isBeforeEffective = todayLocalDateString() < TERMS_EFFECTIVE_DATE;
  if (!isBeforeEffective || dismissed === TERMS_EFFECTIVE_DATE) return null;

  const handleDismiss = () => {
    writeDismissed(TERMS_EFFECTIVE_DATE);
    setDismissed(TERMS_EFFECTIVE_DATE);
  };

  return (
    <div className="terms-notice" role="note">
      <div className="terms-notice-body">
        <strong className="terms-notice-title">
          이용약관이 개정됩니다 · {formatMonthDay(TERMS_EFFECTIVE_DATE)} 시행
        </strong>
        <p className="terms-notice-text">
          유료 구독(Pro)·환불 조항이 새로 생기고, 판매 대행자(Paddle) 고지가 추가됩니다.
          시행일 이후에도 계속 이용하면 개정 약관에 동의한 것으로 봅니다. 동의하지
          않으시면 시행일 전에 계정 설정에서 회원 탈퇴하거나{" "}
          {/* 제12조 ②의 동의 간주는 "거부 의사를 밝히지 않으면"이 전제라, 거부 방법을 함께 알린다. */}
          <a href={`mailto:${LEGAL_CONTACT_EMAIL}`}>{LEGAL_CONTACT_EMAIL}</a>으로 알려 주세요.{" "}
          {/* 배너를 닫아 화면을 옮기지 않도록 새 탭에서 연다(ConsentScreen과 같은 이유). */}
          <a href={PATHS.terms} target="_blank" rel="noopener noreferrer">
            이용약관
          </a>{" "}
          ·{" "}
          <a href={PATHS.refund} target="_blank" rel="noopener noreferrer">
            환불정책
          </a>
        </p>
      </div>
      <button
        type="button"
        className="icon-btn terms-notice-dismiss"
        onClick={handleDismiss}
        aria-label="공지 닫기"
      >
        <Icons.X size={14} />
      </button>
    </div>
  );
}
