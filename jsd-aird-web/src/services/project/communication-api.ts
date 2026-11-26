import { httpClient } from '@/services/http/client';

export interface ProjectCommunication {
  id: string;
  projectId: string;
  occurredAt: string;
  communicationMethod: string;
  communicator: string;
  content: string;
  customFields: Record<string, string>;
  version: number;
}

export type CommunicationInput = Pick<ProjectCommunication, 'occurredAt' | 'communicationMethod' | 'communicator' | 'content' | 'customFields'> & {
  version?: number;
};

export async function listProjectCommunications(projectId: string, page = 1, size = 20) {
  const { data } = await httpClient.get<{ data: { items: ProjectCommunication[]; total: number } }>(
    `/api/v1/projects/${projectId}/communications`, { params: { page, size } },
  );
  return data.data;
}

export async function createProjectCommunication(projectId: string, input: CommunicationInput) {
  await httpClient.post(`/api/v1/projects/${projectId}/communications`, input);
}

export async function updateProjectCommunication(projectId: string, id: string, input: CommunicationInput) {
  await httpClient.put(`/api/v1/projects/${projectId}/communications/${id}`, input);
}

export async function deleteProjectCommunication(projectId: string, id: string, version: number) {
  await httpClient.delete(`/api/v1/projects/${projectId}/communications/${id}`, { params: { version } });
}
