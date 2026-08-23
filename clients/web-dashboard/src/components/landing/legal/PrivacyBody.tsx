import { useLandingLanguage } from "@/components/landing/LandingLanguageProvider";
import { PrivacyBodyEn } from "@/components/landing/legal/PrivacyBodyEn";
import { PrivacyBodyKo } from "@/components/landing/legal/PrivacyBodyKo";

// 언어 스위치 — LegalLayout(LandingLanguageProvider)의 자식으로 렌더되므로
// useLandingLanguage()를 바로 구독할 수 있다.
export function PrivacyBody() {
  const { lang } = useLandingLanguage();
  return lang === "en" ? <PrivacyBodyEn /> : <PrivacyBodyKo />;
}
