import { useLandingLanguage } from "@/components/landing/LandingLanguageProvider";
import { RefundBodyEn } from "@/components/landing/legal/RefundBodyEn";
import { RefundBodyKo } from "@/components/landing/legal/RefundBodyKo";

// 언어 스위치 — LegalLayout(LandingLanguageProvider)의 자식으로 렌더되므로
// useLandingLanguage()를 바로 구독할 수 있다(TermsBody.tsx와 같은 패턴).
export function RefundBody() {
  const { lang } = useLandingLanguage();
  return lang === "en" ? <RefundBodyEn /> : <RefundBodyKo />;
}
