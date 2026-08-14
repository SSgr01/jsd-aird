import { httpClient } from '@/services/http/client';
interface ApiResponse<T> {
  data: T;
}
export interface PageData<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}
export interface Communication {
  id: string;
  recordCode: string;
  name?: string;
  partnerId: string;
  communicatedAt: string;
  internalParticipants?: string;
  communicationMethod: string;
  content: string;
  status: 'OPEN' | 'FOLLOWING' | 'CLOSED';
  customFields?: Record<string, unknown>;
  version: number;
}
export type CommunicationInput = Omit<
  Communication,
  'id' | 'recordCode'
>;
export interface Metric {
  id?: string;
  metricName: string;
  unit?: string;
  comparator: 'EQ' | 'GE' | 'GT' | 'LE' | 'LT' | 'RANGE' | 'TEXT';
  targetValue?: string;
  passingValue?: string;
  keyIndicator?: boolean;
  lowerValue?: number;
  upperValue?: number;
  testStandard?: string;
  priority: 'HIGH' | 'MEDIUM' | 'LOW';
  remark?: string;
  sortOrder: number;
}
export interface Requirement {
  id: string;
  requirementCode: string;
  partnerId: string;
  title?: string;
  rawRequirement?: string;
  urgency?: string;
  raisedAt?: string;
  deliveryDate?: string;
  status: 'DRAFT' | 'CONFIRMED' | 'IN_PROJECT' | 'COMPLETED' | 'CANCELLED';
  projectId?: string;
  metrics: Metric[];
  version: number;
  customStatusName?: string;
  customFields?: Record<string, unknown>;
}
export type RequirementInput = Omit<Requirement, 'id' | 'requirementCode'>;
async function list<T>(path: string, params: Record<string, unknown>) {
  const { data } = await httpClient.get<ApiResponse<PageData<T>>>(path, { params });
  return data.data;
}
export const getCommunications = (params: Record<string, unknown>) =>
  list<Communication>('/api/v1/crm/communications', params);
export const getRequirements = (params: Record<string, unknown>) =>
  list<Requirement>('/api/v1/crm/requirements', params);
export async function createCommunication(v: CommunicationInput) {
  await httpClient.post('/api/v1/crm/communications', v);
}
export async function updateCommunication(id: string, v: CommunicationInput) {
  await httpClient.put(`/api/v1/crm/communications/${id}`, v);
}
export async function deleteCommunication(id: string, version: number) {
  await httpClient.delete(`/api/v1/crm/communications/${id}?version=${version}`);
}
// 需求
export async function createRequirement(v: RequirementInput) {
  await httpClient.post('/api/v1/crm/requirements', v);
}
export async function updateRequirement(id: string, v: RequirementInput) {
  await httpClient.put(`/api/v1/crm/requirements/${id}`, v);
}
export async function deleteRequirement(id: string, version: number) {
  await httpClient.delete(`/api/v1/crm/requirements/${id}?version=${version}`);
}
