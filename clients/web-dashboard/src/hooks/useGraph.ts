import { useQuery } from "@tanstack/react-query";

import { getProjectGraph } from "@/api/graph";
import { queryKeys } from "./queryKeys";

export function useGraph(projectId: string) {
  return useQuery({
    queryKey: queryKeys.graph(projectId),
    queryFn: () => getProjectGraph(projectId),
  });
}
