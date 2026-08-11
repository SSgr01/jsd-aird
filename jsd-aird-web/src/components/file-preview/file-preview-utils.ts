import { downloadBlob } from '@/services/files';

export type FilePreviewMode = 'pdf' | 'image' | 'text' | 'docx' | 'spreadsheet' | 'unsupported';

export interface FilePreviewDescriptor {
  fileName: string;
  contentType?: string;
  size?: number;
  load: () => Promise<Blob>;
}

export function detectPreviewMode(fileName: string, contentType = ''): FilePreviewMode {
  const extension = fileName.split('.').pop()?.toLowerCase() || '';
  const normalizedType = contentType.toLowerCase();
  if (normalizedType === 'application/pdf' || extension === 'pdf') return 'pdf';
  if (normalizedType.startsWith('image/')) return 'image';
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'tif', 'tiff'].includes(extension)) return 'image';
  if (normalizedType.includes('wordprocessingml.document') || extension === 'docx') return 'docx';
  if (normalizedType.includes('spreadsheetml.sheet') || normalizedType.includes('ms-excel') || ['xls', 'xlsx', 'csv'].includes(extension)) return 'spreadsheet';
  if (normalizedType.startsWith('text/') || ['txt', 'md', 'json', 'xml', 'log'].includes(extension)) return 'text';
  return 'unsupported';
}

export async function downloadPreviewFile(file: FilePreviewDescriptor) {
  const blob = await file.load();
  downloadBlob(blob, file.fileName);
}
