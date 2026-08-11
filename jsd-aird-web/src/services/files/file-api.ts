import { httpClient } from '@/services/http/client';

export async function fetchFileBlob(fileId: string) {
  const response = await httpClient.get<Blob>(`/api/v2/files/${fileId}/content`, {
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
