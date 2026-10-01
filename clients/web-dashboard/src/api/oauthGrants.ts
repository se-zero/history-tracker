import { api } from "./client";
import type { OAuthGrant } from "@/types/api";

// MCP 클라이언트(Claude Code·Codex)에 내준 OAuth 동의 목록 — 계정 페이지 "연결된 앱" 카드가 쓴다.
export async function listOAuthGrants(): Promise<OAuthGrant[]> {
  const { data } = await api.get<OAuthGrant[]>("/me/oauth-grants");
  return data;
}

// 연결 끊기 — 그 앱에 발급된 refresh 토큰·인가가 삭제된다. backend가 `/mcp` 입구에서
// 요청마다 연결 행을 확인하므로 이미 발급된 access 토큰도 즉시 거부된다.
export async function revokeOAuthGrant(id: string): Promise<void> {
  await api.delete(`/me/oauth-grants/${id}`);
}
