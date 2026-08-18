import { useEffect, useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";

import { searchConversations } from "@/api/conversations";
import { queryKeys } from "./queryKeys";

// 검색 시작 최소 글자 수 — 1자 부분 일치는 대화 대부분에 걸려 결과가 무의미해진다.
export const SEARCH_MIN_QUERY_LENGTH = 2;
const DEBOUNCE_MS = 250;

// 타이핑 중 요청 폭주를 막는 디바운스 값
export function useDebouncedValue<T>(value: T, delayMs = DEBOUNCE_MS): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

function searchEnabled(q: string): boolean {
  return q.trim().length >= SEARCH_MIN_QUERY_LENGTH;
}

// 대화 검색. placeholderData로 이전 결과를 유지해 타이핑 중 목록 깜빡임을 막고,
// 검색은 즉각적 피드백이 중요해 재시도하지 않는다.
export function useSearchConversations(projectId: string, q: string) {
  return useQuery({
    queryKey: queryKeys.searchConversations(projectId, q),
    queryFn: () => searchConversations(projectId, q),
    enabled: searchEnabled(q),
    placeholderData: keepPreviousData,
    retry: false,
  });
}
