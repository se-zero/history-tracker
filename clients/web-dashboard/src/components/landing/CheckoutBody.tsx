import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { Link, useSearchParams } from "react-router-dom";

import { GITHUB_AUTHORIZE_URL } from "@/api/auth";
import { useAuth } from "@/auth/AuthProvider";
import { saveReturnPath } from "@/auth/returnPath";
import { useLandingLanguage, type Localized } from "@/components/landing/LandingLanguageProvider";
import { LEGAL_CONTACT_EMAIL } from "@/components/landing/LegalLayout";
import { useCheckoutAvailability } from "@/hooks/useBilling";
import { openPaddleCheckout } from "@/lib/paddle";
import { PATHS } from "@/routes";

// Paddle 거래 ID — `txn_` + 소문자·숫자 26자. 형식이 맞지 않는 값으로는 Paddle.js를 로드하지 않는다.
const TRANSACTION_ID_PATTERN = /^txn_[a-z0-9]{26}$/;

const EMAIL_LINK = <a href={`mailto:${LEGAL_CONTACT_EMAIL}`}>{LEGAL_CONTACT_EMAIL}</a>;

// 결제창을 연 뒤의 상태. 그 앞의 invalid·preparing·unavailable은 쿼리·서버 응답에서 파생한다.
type DialogPhase = "opening" | "open" | "completed" | "closed" | "error";
type Phase = "invalid" | "preparing" | "unavailable" | DialogPhase;

const COPY: Localized<{
  messages: Record<Phase, ReactNode>;
  pricing: string;
  support: string;
  reopen: string;
  retry: string;
  checkAccount: string;
}> = {
  ko: {
    messages: {
      invalid:
        "결제 링크가 아니거나 주소 일부가 빠졌습니다. 받은 메일의 링크를 다시 눌러 주세요. 새로 구독하려면 요금 페이지를 보세요.",
      preparing: "결제창을 준비하는 중…",
      unavailable: <>지금은 결제창을 열 수 없습니다. 계속되면 {EMAIL_LINK}으로 알려 주세요.</>,
      opening: "결제창을 여는 중…",
      open: "결제창에서 진행해 주세요.",
      completed: "처리가 끝났습니다. 계정에 반영되기까지 몇 분 걸릴 수 있습니다.",
      // 은행 앱 확인을 거치는 결제는 창을 닫은 뒤에 완료될 수 있어 처리 여부를 단정하지 않는다.
      closed: "결제창을 닫았습니다. 결제를 마치지 않았다면 다시 열어 진행하세요.",
      error: (
        <>결제창을 열지 못했습니다. 잠시 후 다시 시도하고, 계속되면 {EMAIL_LINK}으로 알려 주세요.</>
      ),
    },
    pricing: "요금",
    support: "지원",
    reopen: "결제창 다시 열기",
    retry: "다시 시도",
    checkAccount: "계정 설정에서 확인",
  },
  en: {
    messages: {
      invalid:
        "This isn't a payment link, or part of the address is missing. Open the link from your email again. To start a new subscription, see the pricing page.",
      preparing: "Preparing checkout…",
      unavailable: <>Checkout can't be opened right now. If this continues, email {EMAIL_LINK}.</>,
      opening: "Opening checkout…",
      open: "Continue in the checkout window.",
      completed: "Done. It may take a few minutes to show up in your account.",
      closed: "You closed the checkout. If you didn't finish, reopen it to continue.",
      error: (
        <>Couldn't open checkout. Try again in a moment; if it keeps failing, email {EMAIL_LINK}.</>
      ),
    },
    pricing: "Pricing",
    support: "Support",
    reopen: "Reopen checkout",
    retry: "Try again",
    checkAccount: "Check in account settings",
  },
};

// 결제 본문 — Paddle이 만든 결제 링크(`?_ptxn=`)의 착지점. 로그인 없이 열리므로 토큰은 공개
// availability 응답에서 받는다. 신규 결제 스위치(checkoutAvailable)는 보지 않고 clientToken만
// 본다 — 신규 결제를 닫아도 기존 구독자가 받은 복구 링크는 열려야 한다.
// 페이지에 들어오면 결제창을 한 번 자동으로 열고, 닫으면 버튼으로 다시 연다.
export function CheckoutBody() {
  const { lang } = useLandingLanguage();
  const { status } = useAuth();
  const availability = useCheckoutAvailability();
  const [searchParams] = useSearchParams();
  // 처음 값을 "opening"으로 둔다 — 토큰이 준비되는 즉시 아래 effect가 열기를 시작하므로,
  // 별도 대기 상태를 두면 그 사이 한 프레임이 빈 안내로 그려진다.
  const [dialogPhase, setDialogPhase] = useState<DialogPhase>("opening");
  // main.tsx가 StrictMode라 개발 모드에서는 effect가 두 번 돈다. 자동 열기를 한 번으로 막는다.
  const autoOpened = useRef(false);
  const t = COPY[lang];

  const ptxn = searchParams.get("_ptxn");
  const transactionId = ptxn !== null && TRANSACTION_ID_PATTERN.test(ptxn) ? ptxn : null;
  const clientToken = availability.data?.clientToken ?? null;
  const environment = availability.data?.environment ?? "";

  const openCheckout = useCallback(async () => {
    if (!transactionId || !clientToken) return;
    setDialogPhase("opening");
    try {
      await openPaddleCheckout({
        token: clientToken,
        environment,
        transactionId,
        onEvent: (name) => {
          if (name === "checkout.loaded") {
            setDialogPhase((phase) => (phase === "opening" ? "open" : phase));
          } else if (name === "checkout.completed") {
            setDialogPhase("completed");
          } else if (name === "checkout.error") {
            setDialogPhase("error");
          } else if (name === "checkout.closed") {
            // 결제를 마친 뒤에도 창을 닫으면 closed가 따라온다. 완료는 덮어쓰지 않는다.
            // 오류도 덮어쓰지 않는다 — 닫은 뒤에도 문의처가 남아야 한다.
            setDialogPhase((phase) => (phase === "completed" || phase === "error" ? phase : "closed"));
          }
        },
      });
    } catch {
      setDialogPhase("error");
    }
  }, [transactionId, clientToken, environment]);

  useEffect(() => {
    if (!transactionId || !clientToken || autoOpened.current) return;
    autoOpened.current = true;
    void openCheckout();
  }, [transactionId, clientToken, openCheckout]);

  // 요청이 오류로 끝나면 data가 없어 clientToken도 null이라 unavailable로 모인다.
  const phase: Phase = !transactionId
    ? "invalid"
    : availability.isPending
      ? "preparing"
      : !clientToken
        ? "unavailable"
        : dialogPhase;

  return (
    <section className="lp-legal-section">
      <p className="lp-legal-notice" role="status">
        {t.messages[phase]}
      </p>
      {phase === "invalid" && (
        <ul>
          <li>
            <Link to={PATHS.pricing}>{t.pricing}</Link>
          </li>
          <li>
            <Link to={PATHS.support}>{t.support}</Link>
          </li>
        </ul>
      )}
      {(phase === "closed" || phase === "error") && (
        <p>
          <button type="button" className="lp-btn lp-btn--primary" onClick={() => void openCheckout()}>
            {phase === "closed" ? t.reopen : t.retry}
          </button>
        </p>
      )}
      {phase === "completed" && (
        <p>
          {status === "authenticated" ? (
            <Link to={PATHS.account}>{t.checkAccount}</Link>
          ) : (
            <a href={GITHUB_AUTHORIZE_URL} onClick={() => saveReturnPath(PATHS.account)}>
              {t.checkAccount}
            </a>
          )}
        </p>
      )}
    </section>
  );
}
