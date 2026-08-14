import { httpClient } from '@/services/http/client';
import type { ApiResponse } from '@/types/api';

export type ExperimentStatus='DRAFT'|'PENDING'|'IN_PROGRESS'|'PENDING_REVIEW'|'RETURNED'|'COMPLETED'|'VOIDED';
export interface ExperimentSummary{id:string;experimentNo:string;title:string;categoryName?:string;sourceType:string;status:ExperimentStatus;projectId?:string;stageId?:string;taskId?:string;ownerName:string;experimentDate:string;versionNo:number;revision:number;updatedAt:string}
export interface ExperimentModel{title?:string;purpose?:string;plan?:string;dynamicValues?:Record<string,unknown>;formulaItems?:Array<Record<string,unknown>>;processSteps?:Array<Record<string,unknown>>;testResults?:Array<Record<string,unknown>>;events?:Array<Record<string,unknown>>;conclusion?:Record<string,unknown>}
export interface ExperimentDetail{summary:ExperimentSummary;currentVersionId:string;templateVersionId?:string;templateSnapshotHash?:string;templateSnapshot:Record<string,unknown>;editModel:ExperimentModel;reviews:Array<Record<string,unknown>>;attachments:Array<Record<string,unknown>>}
export interface Page<T>{items:T[];page:number;size:number;total:number;totalPages:number}
export interface Category{id:string;code:string;name:string;description:string;active:boolean;revision:number}
const data=<T>(r:{data:ApiResponse<T>})=>r.data.data;
export async function listExperiments(params:Record<string,unknown>){return data(await httpClient.get<ApiResponse<Page<ExperimentSummary>>>('/api/v1/experiments',{params}));}
export async function getExperiment(id:string){return data(await httpClient.get<ApiResponse<ExperimentDetail>>(`/api/v1/experiments/${id}/edit-model`));}
export async function createExperiment(input:Record<string,unknown>){return data(await httpClient.post<ApiResponse<ExperimentSummary>>('/api/v1/experiments',input));}
export async function saveExperiment(id:string,input:Record<string,unknown>){return data(await httpClient.post<ApiResponse<ExperimentDetail>>(`/api/v1/experiments/${id}/draft`,input));}
export async function actExperiment(id:string,action:'start'|'submit-review'|'approve'|'return'|'void',revision:number,comment?:string){return data(await httpClient.post<ApiResponse<ExperimentDetail>>(`/api/v1/experiments/${id}/${action}`,{revision,comment}));}
export async function listVersions(id:string){return data(await httpClient.get<ApiResponse<Array<Record<string,unknown>>>>(`/api/v1/experiments/${id}/versions`));}
export async function compareVersions(id:string,from:number,to:number){return data(await httpClient.get<ApiResponse<Record<string,unknown>>>(`/api/v1/experiments/${id}/versions/compare`,{params:{from,to}}));}
export async function createRevision(id:string,revision:number,reason:string){return data(await httpClient.post<ApiResponse<ExperimentDetail>>(`/api/v1/experiments/${id}/versions`,{revision,reason}));}
export async function listAudits(id:string){return data(await httpClient.get<ApiResponse<Array<Record<string,unknown>>>>(`/api/v1/experiments/${id}/audits`));}
export async function listCategories(includeInactive=false){return data(await httpClient.get<ApiResponse<Category[]>>('/api/v1/experiment-categories',{params:{includeInactive}}));}
export async function createCategory(input:{code:string;name:string;description:string}){return data(await httpClient.post<ApiResponse<Category>>('/api/v1/experiment-categories',input));}
export interface StagedFile{fileId:string;originalName:string;contentType:string;size:number;sha256:string;status:'STAGED'}
export async function stageExperimentFile(file:File){const body=new FormData();body.append('file',file);return data(await httpClient.post<ApiResponse<StagedFile>>('/api/v2/files/staged?kind=EXPERIMENT_SOURCE',body));}
