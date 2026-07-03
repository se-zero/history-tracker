import { useEffect, useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";

import { searchConversations } from "@/api/conversations";
import { searchGraphNodes } from "@/api/graph";
import { queryKeys } from "./queryKeys";

// 검색 시작 최소 글자 수 — ai-engine 한글 bigram 인덱스도 2자부터 안정적으로 매치된다.
export const SEARCH_MIN_QUERY_LENGTH = 2;
const DEBOUNCE_MS = 250;
const NODE_RESULT_LIMIT = 10;

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

// 그래프 노드 검색 — 대화 검색과 병렬 실행되며, ai-engine 장애(502) 시
// 이 쿼리만 실패하고 대화 검색 결과는 그대로 노출된다 (우아한 성능 저하).
export function useSearchGraphNodes(projectId: string, q: string) {
  return useQuery({
    queryKey: queryKeys.searchNodes(projectId, q),
    queryFn: () => searchGraphNodes(projectId, q, NODE_RESULT_LIMIT),
    enabled: searchEnabled(q),
    placeholderData: keepPreviousData,
    retry: false,
  });
}
