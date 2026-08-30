import { useEffect } from "react";
import { useLocation } from "react-router-dom";

// SPA는 첫 페인트 전에 브라우저의 기본 앵커 이동이 끝나 대상이 없다.
// /landing#in-slack · /privacy#slack 처럼 외부(Slack 앱 설정·심사)가 붙인 해시가
// 착지해야 하므로, 공개 페이지가 마운트된 뒤에 직접 스크롤한다.
// 해시가 없으면 최상단 — 랜딩에서 스크롤한 채로 푸터 링크를 누르면 문서 중간이
// 첫 화면이 되는 것을 막는다.
export function useDocumentHashScroll() {
  const { hash } = useLocation();
  useEffect(() => {
    if (!hash) {
      window.scrollTo(0, 0);
      return;
    }
    document.getElementById(decodeURIComponent(hash.slice(1)))?.scrollIntoView();
  }, [hash]);
}
