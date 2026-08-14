import { httpClient } from '@/services/http/client';

interface ApiResponse<T> {
  data: T;
}

interface PageData<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface MeetingMinutes {
  id: string;
  projectId: string;
  title: string;
  attendees: string[];
  summary?: string;
  occurredAt?: string;
  archivedToKb: boolean;
  version: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface MeetingMinutesInput {
  projectId?: string;
  title: string;
  attendees?: string[];
  summary?: string;
  occurredAt?: string;
  version?: number;
}

export interface MeetingMinutesQuery {
  page?: number;
  size?: number;
}

function unwrap<T>(payload: ApiResponse<T>): T {
  return payload.data;
}

export async function listMeetings(projectId: string, query: MeetingMinutesQuery): Promise<PageData<MeetingMinutes>> {
  const { data } = await httpClient.get<ApiResponse<PageData<MeetingMinutes>>>(`/api/v1/projects/${projectId}/meetings`, {
    params: {
      page: query.page ?? 1,
      size: query.size ?? 50,
    },
  });
  return data.data;
}

export async function getMeeting(id: string): Promise<MeetingMinutes> {
  const { data } = await httpClient.get<ApiResponse<MeetingMinutes>>(`/api/v1/projects/_/meetings/${id}`);
  return unwrap(data);
}

export async function createMeeting(projectId: string, input: MeetingMinutesInput): Promise<MeetingMinutes> {
  const { data } = await httpClient.post<ApiResponse<MeetingMinutes>>(
    `/api/v1/projects/${projectId}/meetings`,
    { ...input, projectId },
  );
  return unwrap(data);
}

export async function updateMeeting(id: string, input: MeetingMinutesInput): Promise<void> {
  await httpClient.put(`/api/v1/projects/_/meetings/${id}`, input);
}

export async function deleteMeeting(id: string, version: number): Promise<void> {
  await httpClient.delete(`/api/v1/projects/_/meetings/${id}`, { params: { version } });
}

export async function archiveMeetingToKb(id: string): Promise<void> {
  await httpClient.post(`/api/v1/projects/_/meetings/${id}/archive-to-kb`);
}