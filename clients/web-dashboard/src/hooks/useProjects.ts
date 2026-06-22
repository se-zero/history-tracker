import { useQuery } from "@tanstack/react-query";

import { listProjects } from "@/api/projects";
import { queryKeys } from "./queryKeys";

// 프로젝트 목록 조회. App 루트에서는 인증된 경우에만 돌도록 enabled를 넘긴다.
export function useProjects(options?: { enabled?: boolean }) {
  return useQuery({
    queryKey: queryKeys.projects(),
    queryFn: listProjects,
    enabled: options?.enabled,
  });
}
