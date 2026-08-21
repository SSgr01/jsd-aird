import { httpClient } from '@/services/http/client';
import type { ApiResponse } from '@/types/api';

export interface StagedFile { fileId: string; originalName: string; contentType: string; size: number; sha256: string; status: string }

export async function stageFile(file: File, kind: 'KNOWLEDGE' | 'SNAPSHOT' | 'IMPORT' | 'SPC_CHART' = 'KNOWLEDGE') {
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

/**
 * Start a same-origin download through the browser's native download
 * pipeline.  This is intentionally separate from the Blob helper used by
 * previews: native links let the browser observe Content-Disposition and
 * emit a real download event for regression tests and assistive tooling.
 */
export function triggerNativeDownload(path: string, fileName?: string) {
  const anchor = document.createElement('a');
  anchor.href = path;
  if (fileName) anchor.download = fileName;
  anchor.rel = 'noopener';
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  window.setTimeout(() => anchor.remove(), 0);
}

export function downloadFile(fileId: string, fileName: string) {
  triggerNativeDownload(`/api/v1/files/${encodeURIComponent(fileId)}/content`, fileName);
}
