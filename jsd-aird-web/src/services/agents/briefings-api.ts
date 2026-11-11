import { httpClient } from '@/services/http/client';
import type { ApiResponse } from '@/types/api';

export type BriefingKind = 'project-morning' | 'research-weekly' | 'experiment-review';

export interface BriefingPeriod {
  start: string | null;
  end: string;
  time_zone: string;
}

export interface BriefingCard {
  kind: BriefingKind;
  title: string;
  task_status: string;
  state: string;
  latest: { status: string; window: BriefingPeriod | null; generated_at: string } | null;
  result: {
    run_id: string;
    session_id: string;
    turn: number;
    generated_at: string;
    window: BriefingPeriod;
    headline: string;
    summary: string;
    actions: { text: string; href?: string }[];
    href: string;
  } | null;
}

export async function getBriefings(signal?: AbortSignal) {
  const response = await httpClient.get<ApiResponse<{ items: BriefingCard[] }>>('/api/v1/agents/briefings', { signal });
  return response.data.data;
}
