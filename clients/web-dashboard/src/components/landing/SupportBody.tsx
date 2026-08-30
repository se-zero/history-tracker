import { Link } from "react-router-dom";

import {
  LEGAL_CONTACT_EMAIL,
  LEGAL_CONTACT_URL,
} from "@/components/landing/LegalLayout";
import { useLandingLanguage, type Localized } from "@/components/landing/LandingLanguageProvider";
import { PATHS } from "@/routes";

const COPY: Localized<{
  contactHeading: string;
  contactBody: string;
  issuesLabel: string;
  helpHeading: string;
  helpItem1: string;
  helpItem2: string;
  helpItem3: string;
  relatedHeading: string;
  privacy: string;
  terms: string;
  slack: string;
}> = {
  ko: {
    contactHeading: "문의",
    contactBody:
      "이메일이 기본 창구입니다. 어떤 프로젝트·연동인지, 무엇을 하려다 막혔는지를 적어 주시면 더 빨리 짚을 수 있습니다.",
    issuesLabel: "이슈 트래커",
    helpHeading: "이런 걸 도와드릴 수 있습니다",
    helpItem1: "연결·수집이 멈추거나 실패할 때",
    helpItem2: "연동 해제, 데이터 삭제, 회원 탈퇴 요청",
    helpItem3: "개인정보 열람·정정·삭제 등 권리 행사",
    relatedHeading: "관련 문서",
    privacy: "개인정보처리방침",
    terms: "이용약관",
    slack: "Slack에서 쓰는 방법",
  },
  en: {
    contactHeading: "Contact",
    contactBody:
      "Email is the primary channel. Naming the project and connection, and what you were trying to do, helps us get there faster.",
    issuesLabel: "Issue tracker",
    helpHeading: "What we can help with",
    helpItem1: "A connection or collection that has stalled or failed",
    helpItem2: "Disconnecting a source, deleting data, or closing an account",
    helpItem3: "Requests to access, correct, or delete personal information",
    relatedHeading: "Related",
    privacy: "Privacy Policy",
    terms: "Terms of Service",
    slack: "How to use whycode in Slack",
  },
};

// 지원 본문 — 조 번호 없는 짧은 산문. LegalLayout 안에서 렌더되므로 언어는 Provider에서 읽는다.
export function SupportBody() {
  const { lang } = useLandingLanguage();
  const t = COPY[lang];

  return (
    <>
      <section className="lp-legal-section">
        <h2 className="lp-legal-heading">{t.contactHeading}</h2>
        <p>{t.contactBody}</p>
        <ul>
          <li>
            {lang === "en" ? "Email" : "이메일"} —{" "}
            <a href={`mailto:${LEGAL_CONTACT_EMAIL}`}>{LEGAL_CONTACT_EMAIL}</a>
          </li>
          <li>
            {t.issuesLabel} —{" "}
            <a href={LEGAL_CONTACT_URL} target="_blank" rel="noreferrer">
              {LEGAL_CONTACT_URL}
            </a>
          </li>
        </ul>
      </section>

      <section className="lp-legal-section">
        <h2 className="lp-legal-heading">{t.helpHeading}</h2>
        <ul>
          <li>{t.helpItem1}</li>
          <li>{t.helpItem2}</li>
          <li>{t.helpItem3}</li>
        </ul>
      </section>

      <section className="lp-legal-section">
        <h2 className="lp-legal-heading">{t.relatedHeading}</h2>
        <ul>
          <li>
            <Link to={PATHS.privacy}>{t.privacy}</Link>
          </li>
          <li>
            <Link to={PATHS.terms}>{t.terms}</Link>
          </li>
          <li>
            <Link to={`${PATHS.landing}#in-slack`}>{t.slack}</Link>
          </li>
        </ul>
      </section>
    </>
  );
}
