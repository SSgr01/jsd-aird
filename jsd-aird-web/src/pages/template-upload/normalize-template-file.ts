import type { TemplateFormat } from '@/features/template-workspace/types';

const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

export type TemplateSourceFormat = 'XLSX' | 'DOCX' | 'XLS' | 'CSV' | 'DOC';

export type NormalizedTemplateFile = {
  file: File;
  format: TemplateFormat;
  sourceFormat: TemplateSourceFormat;
};

/**
 * The template editor has two canonical workspaces: XLSX and DOCX. Legacy
 * tabular files are converted in the browser before staging so the original
 * upload can still be validated and the editor always receives an OOXML file.
 */
export async function normalizeTemplateFile(file: File): Promise<NormalizedTemplateFile> {
  const sourceFormat = sourceFormatOf(file.name);
  if (sourceFormat === 'XLSX' || sourceFormat === 'DOCX') {
    return { file, format: sourceFormat, sourceFormat };
  }
  if (sourceFormat === 'DOC') {
    throw new Error('DOC 是旧版 Word 格式，当前无法在浏览器中安全转换；请先另存为 DOCX 后再上传');
  }

  try {
    const xlsx = await import('xlsx');
    const workbook = xlsx.read(await file.arrayBuffer(), { type: 'array', cellDates: true });
    if (!workbook.SheetNames.length) throw new Error('文件中没有可识别的工作表');
    const output: unknown = xlsx.write(workbook, { bookType: 'xlsx', type: 'array', compression: true });
    const bytes = output instanceof ArrayBuffer
      ? new Uint8Array(output)
      : ArrayBuffer.isView(output)
        ? new Uint8Array(output.buffer, output.byteOffset, output.byteLength)
        : undefined;
    if (!bytes) throw new Error('标准化结果不是有效的二进制文件');
    const baseName = file.name.replace(/\.(?:xls|csv)$/i, '');
    const normalized = new File([new Uint8Array(bytes).buffer], `${baseName}.xlsx`, {
      type: XLSX_MIME,
      lastModified: file.lastModified,
    });
    return { file: normalized, format: 'XLSX', sourceFormat };
  } catch (error) {
    throw new Error(error instanceof Error ? `无法将 ${sourceFormat} 标准化为 XLSX：${error.message}` : `无法将 ${sourceFormat} 标准化为 XLSX`, { cause: error });
  }
}

export function sourceFormatOf(fileName: string): TemplateSourceFormat {
  const lower = fileName.toLowerCase();
  if (lower.endsWith('.xlsx')) return 'XLSX';
  if (lower.endsWith('.docx')) return 'DOCX';
  if (lower.endsWith('.xls')) return 'XLS';
  if (lower.endsWith('.csv')) return 'CSV';
  if (lower.endsWith('.doc')) return 'DOC';
  throw new Error('仅支持 XLSX、XLS、CSV、DOCX 或 DOC 文件');
}
