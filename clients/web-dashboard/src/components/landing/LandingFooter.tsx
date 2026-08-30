import { Link } from "react-router-dom";

import { GITHUB_AUTHORIZE_URL } from "@/api/auth";
import { useAuth } from "@/auth/AuthProvider";
import { useLandingLanguage, type Localized } from "@/components/landing/LandingLanguageProvider";
import { PATHS } from "@/routes";

// 문자열 소사전 — login/openApp은 LandingHeader COPY와 같은 값이다(같은 인증 상태 분기,
// 같은 문구를 반복 노출).
const COPY: Localized<{
  terms: string;
  privacy: string;
  support: string;
  login: string;
  openApp: string;
}> = {
  ko: {
    terms: "이용약관",
    privacy: "개인정보처리방침",
    support: "지원",
    login: "로그인",
    openApp: "whycode 열기",
  },
  en: {
    terms: "Terms of Service",
    privacy: "Privacy Policy",
    support: "Support",
    login: "Log in",
    openApp: "Open whycode",
  },
};

// 랜딩 푸터 — 페이지의 마지막 섹션. 제품명 텍스트 / 약관·개인정보·지원 / 로그인 링크를
// 두는 미니멀 한 줄(저장소 GitHub 링크는 2026-08-24 사용자 지시로 제거 — 옛 저장소명을
// 가리키고 있었고, 공개 저장소 노출 여부는 별개 판단). 약관 링크가 푸터에만 있으므로
// (법적 고지의 관례적 위치) 랜딩뿐 아니라 약관·개인정보·지원 페이지 자신도 이 푸터를 쓴다 —
// 공개 문서가 서로를 오갈 수 있어야 한다.
// 로그인 링크는 헤더(LandingHeader)와 같은 인증 상태 분기를 쓴다 —
// 비로그인이면 GitHub OAuth 직행, 로그인 상태면 제품으로 바로 이동.
export function LandingFooter() {
  const { status } = useAuth();
  const { lang } = useLandingLanguage();
  const t = COPY[lang];

  return (
    <footer className="lp-footer">
      <div className="lp-footer-inner">
        <span className="lp-footer-brand">whycode</span>
        <div className="lp-footer-links">
          <Link className="lp-footer-link" to={PATHS.terms}>
            {t.terms}
          </Link>
          <Link className="lp-footer-link" to={PATHS.privacy}>
            {t.privacy}
          </Link>
          <Link className="lp-footer-link" to={PATHS.support}>
            {t.support}
          </Link>
          {status === "authenticated" ? (
            <Link className="lp-footer-link" to={PATHS.root}>
              {t.openApp}
            </Link>
          ) : (
            <a className="lp-footer-link" href={GITHUB_AUTHORIZE_URL}>
              {t.login}
            </a>
          )}
        </div>
      </div>
    </footer>
  );
}
