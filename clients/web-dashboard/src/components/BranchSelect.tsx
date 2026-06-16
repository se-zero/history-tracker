import { useQuery } from "@tanstack/react-query";

import { listRepositoryBranches } from "@/api/github";

// 저장소 브랜치 선택 드롭다운. 연결 시점에만 마운트되어 해당 저장소의 브랜치를 lazy 로드한다.
export function BranchSelect({
  installationId,
  owner,
  repo,
  value,
  onChange,
  disabled,
}: {
  installationId: string;
  owner: string;
  repo: string;
  value: string;
  onChange: (branch: string) => void;
  disabled?: boolean;
}) {
  const branchesQuery = useQuery({
    queryKey: ["github", "branches", installationId, owner, repo],
    queryFn: () => listRepositoryBranches(installationId, owner, repo),
  });
  const branches = branchesQuery.data ?? [];
  // 기본 브랜치(value)가 목록에 없거나 로딩 중일 때도 선택값이 비지 않도록 포함
  const options =
    value && !branches.includes(value) ? [value, ...branches] : branches;

  return (
    <select
      className="branch-select"
      value={value}
      onChange={(e) => onChange(e.target.value)}
      disabled={disabled || branchesQuery.isLoading}
      title="수집할 브랜치"
    >
      {branchesQuery.isLoading ? (
        <option value={value}>{value || "브랜치 불러오는 중…"}</option>
      ) : (
        options.map((branch) => (
          <option key={branch} value={branch}>
            {branch}
          </option>
        ))
      )}
    </select>
  );
}
