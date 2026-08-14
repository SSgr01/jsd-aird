import { httpClient } from '@/services/http/client';
import type { ApiResponse } from '@/types/api';

export interface StagedFile { fileId: string; originalName: string; contentType: string; size: number; sha256: string; status: string }

export async function stageFile(file: File, kind: 'KNOWLEDGE' | 'SNAPSHOT' | 'IMPORT' = 'KNOWLEDGE') {
  const body = new FormData();
  body.append('file', file);
  const response = await httpClient.post<ApiResponse<StagedFile>>('/api/v1/files/staged', body, { params: { kind } });
  return response.data.data;
}

export async function fetchFileBlob(fileId: string) {
  const response = await httpClient.get<Blob>(`/api/v1/files/${fileId}/content`, {
    responseType: 'blob',
  });
  return response.data;
}

export function downloadBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName || 'download';
  anchor.rel = 'noopener';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export async function downloadFile(fileId: string, fileName: string) {
  const blob = await fetchFileBlob(fileId);
  downloadBlob(blob, fileName);
}
