import { httpClient } from '@/services/http/client';

export interface HealthResponse {
  status: string;
}

export async function getHealth(): Promise<HealthResponse> {
  const response = await httpClient.get<HealthResponse>('/actuator/health');
  return response.data;
}
