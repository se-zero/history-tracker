import { api } from "./client";
import type { Actor, ActorDecision, ActorDetail } from "@/types/api";

export async function getActors(projectId: string): Promise<Actor[]> {
  const { data } = await api.get<{ actors: Actor[] }>(`/projects/${projectId}/actors`);
  return data.actors;
}

export async function getActorDetail(projectId: string, actorUuid: string): Promise<ActorDetail> {
  const { data } = await api.get<ActorDetail>(`/projects/${projectId}/actors/${actorUuid}`);
  return data;
}

export async function mergeActors(
  projectId: string,
  input: { uuidA: string; uuidB: string; note?: string },
): Promise<void> {
  await api.post(`/projects/${projectId}/actors/merge`, input);
}

export async function renameActor(
  projectId: string,
  input: { actorUuid: string; name: string },
): Promise<void> {
  await api.post(`/projects/${projectId}/actors/rename`, input);
}

export async function splitActor(
  projectId: string,
  input: { actorUuid: string; sourceIds: string[] },
): Promise<void> {
  await api.post(`/projects/${projectId}/actors/split`, input);
}

export async function getActorDecisions(projectId: string): Promise<ActorDecision[]> {
  const { data } = await api.get<{ decisions: ActorDecision[] }>(
    `/projects/${projectId}/actors/decisions`,
  );
  return data.decisions;
}

export async function unmergeActors(projectId: string, decisionId: string): Promise<void> {
  await api.post(`/projects/${projectId}/actors/unmerge`, { decisionId });
}

export async function revokeActorDecision(
  projectId: string,
  decisionId: string,
): Promise<void> {
  await api.delete(`/projects/${projectId}/actors/decisions/${decisionId}`);
}
