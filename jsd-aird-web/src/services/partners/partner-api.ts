import { httpClient } from '@/services/http/client';
export type PartnerStatus = 'ACTIVE' | 'INACTIVE';
export interface PartnerContact {
  id: string;
  partnerId: string;
  name: string;
  department?: string;
  title?: string;
  phone?: string;
  email?: string;
  responsibility?: string;
  wechat?: string;
  members?: string;
  assignedProjectIds?: string[];
  manualTeamMembers?: string[];
  customFields?: Record<string, unknown>;
  primaryContact: boolean;
  status: PartnerStatus;
  validFrom?: string;
  validTo?: string;
  version: number;
}
function parseContactExtras(contact: PartnerContact): PartnerContact {
  return {
    ...contact,
    wechat: contact.wechat ?? '',
    members: contact.members ?? '',
    assignedProjectIds: contact.assignedProjectIds ?? [],
  };
}
function buildContactInputExtras(input: ContactInput): ContactInput {
  const { wechat, members, assignedProjectIds, customFields, manualTeamMembers, ...rest } = input;
  return { ...rest, customFields, wechat, members, assignedProjectIds };
}
export interface BusinessPartner {
  id: string;
  partnerCode: string;
  name: string;
  industry?: string;
  address?: string;
  status: PartnerStatus;
  remark?: string;
  customerLevel?: string;
  cooperationStatus?: string;
  mainBusiness?: string;
  customFields?: Record<string, unknown>;
  contacts: PartnerContact[];
  version: number;
  createdAt: string;
  updatedAt: string;
  ownerNames?: string[];
  requirementCount?: number;
  projectCount?: number;
  latestFollowUpAt?: string;
}
export interface PartnerInput {
  partnerCode: string;
  name: string;
  industry?: string;
  address?: string;
  remark?: string;
  customerLevel?: string;
  cooperationStatus?: string;
  mainBusiness?: string;
  customFields?: Record<string, unknown>;
  version?: number;
}
export interface ContactInput {
  name: string;
  department?: string;
  title?: string;
  phone?: string;
  email?: string;
  responsibility?: string;
  wechat?: string;
  members?: string;
  assignedProjectIds?: string[];
  manualTeamMembers?: string[];
  customFields?: Record<string, unknown>;
  primaryContact: boolean;
  validFrom?: string;
  validTo?: string;
  version?: number;
}
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
export async function getPartners(params: Record<string, unknown>) {
  const { data } = await httpClient.get<ApiResponse<PageData<BusinessPartner>>>(
    '/api/v1/business-partners',
    { params },
  );
  return data.data;
}
export async function getPartner(id: string) {
  const { data } = await httpClient.get<ApiResponse<BusinessPartner>>(`/api/v1/business-partners/${id}`);
  return data.data;
}
export async function createPartner(input: PartnerInput) {
  const { data } = await httpClient.post<ApiResponse<{ id: string; version: number }>>('/api/v1/business-partners', input);
  return data.data;
}
export async function updatePartner(id: string, input: PartnerInput) {
  await httpClient.put(`/api/v1/business-partners/${id}`, input);
}
export interface PartnerCopyResult {
  sourceId: string;
  newId: string;
  partnerCode: string;
}
export async function copyPartners(ids: string[]) {
  const { data } = await httpClient.post<ApiResponse<PartnerCopyResult[]>>(
    '/api/v1/business-partners/bulk-copy',
    { ids },
  );
  return data.data;
}
export async function changePartnerStatus(id: string, status: PartnerStatus, version: number) {
  await httpClient.patch(`/api/v1/business-partners/${id}/status`, { status, version });
}
// 负责人
export async function getPartnerContacts(partnerId: string) {
  const { data } = await httpClient.get<ApiResponse<PartnerContact[]>>(
    `/api/v1/business-partners/${partnerId}/contacts`,
  );
  return data.data.map(parseContactExtras);
}
export async function createContact(partnerId: string, input: ContactInput) {
  await httpClient.post(`/api/v1/business-partners/${partnerId}/contacts`, buildContactInputExtras(input));
}
export async function updateContact(partnerId: string, contactId: string, input: ContactInput) {
  await httpClient.put(`/api/v1/business-partners/${partnerId}/contacts/${contactId}`, buildContactInputExtras(input));
}
export async function changeContactStatus(partnerId: string, contact: PartnerContact) {
  await httpClient.patch(`/api/v1/business-partners/${partnerId}/contacts/${contact.id}/status`, {
    status: contact.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
    version: contact.version,
  });
}
// 联系人-项目关联向量
export interface ContactProjectVector {
  partnerId: string;
  contactId: string;
  contactName: string;
  projectId: string;
  projectCode: string;
  projectName: string;
  projectOwner?: string;
  projectStatus?: string;
  currentStageName?: string;
  progress?: number;
}
export async function getContactProjectVectors(partnerId: string) {
  const { data } = await httpClient.get<ApiResponse<ContactProjectVector[]>>(
    `/api/v1/business-partners/${partnerId}/contacts/projects`,
  );
  return data.data;
}
