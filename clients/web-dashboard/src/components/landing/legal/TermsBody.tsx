import { useLandingLanguage } from "@/components/landing/LandingLanguageProvider";
import { TermsBodyEn } from "@/components/landing/legal/TermsBodyEn";
import { TermsBodyKo } from "@/components/landing/legal/TermsBodyKo";

// 언어 스위치 — LegalLayout(LandingLanguageProvider)의 자식으로 렌더되므로
// useLandingLanguage()를 바로 구독할 수 있다.
export function TermsBody() {
  const { lang } = useLandingLanguage();
  return lang === "en" ? <TermsBodyEn /> : <TermsBodyKo />;
}
