import { downloadBlob } from '@/services/files';
import { buildSpreadsheetPreview, detectPreviewMode, downloadPreviewFile, excelColumnLabel } from './file-preview-utils';

vi.mock('@/services/files', () => ({
  downloadBlob: vi.fn(),
}));

describe('file preview utilities', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it.each([
    ['manual.pdf', 'application/octet-stream', 'pdf'],
    ['photo.png', '', 'image'],
    ['interview.mp3', 'audio/mpeg', 'audio'],
    ['notes.docx', '', 'docx'],
    ['table.xlsx', '', 'spreadsheet'],
    ['table.csv', 'text/csv', 'spreadsheet'],
    ['readme.md', '', 'text'],
    ['archive.zip', '', 'unsupported'],
  ] as const)('detects %s as %s', (fileName, contentType, expected) => {
    expect(detectPreviewMode(fileName, contentType)).toBe(expected);
  });

  it('loads the original blob and delegates download with the original name', async () => {
    const blob = new Blob(['content'], { type: 'text/plain' });
    const load = vi.fn().mockResolvedValue(blob);

    await downloadPreviewFile({ fileName: '说明.txt', load });

    expect(load).toHaveBeenCalledTimes(1);
    expect(downloadBlob).toHaveBeenCalledWith(blob, '说明.txt');
  });

  it('uses Excel column labels and preserves merged cell spans', () => {
    expect(excelColumnLabel(0)).toBe('A');
    expect(excelColumnLabel(25)).toBe('Z');
    expect(excelColumnLabel(26)).toBe('AA');

    const preview = buildSpreadsheetPreview(
      [['总览', '', ''], ['项目', '数值', '单位']],
      [{ s: { r: 0, c: 0 }, e: { r: 0, c: 2 } }],
    );

    expect(preview.merges).toEqual([expect.objectContaining({ range: 'A1:C1', rowSpan: 1, colSpan: 3, clipped: false })]);
    expect(preview.rows[0]!.cells[0]!).toEqual(expect.objectContaining({ value: '总览', colSpan: 3, rowSpan: 1, hidden: false, mergeRange: 'A1:C1' }));
    expect(preview.rows[0]!.cells[1]!).toEqual(expect.objectContaining({ hidden: true, mergeRange: 'A1:C1' }));
    expect(preview.rows[1]!.rowNumber).toBe(2);
  });

  it('reports a merge that extends beyond the preview limit as clipped', () => {
    const preview = buildSpreadsheetPreview(
      Array.from({ length: 3 }, () => ['', '', '']),
      [{ s: { r: 0, c: 0 }, e: { r: 2, c: 4 } }],
      0,
      0,
      2,
      3,
    );

    expect(preview.truncated).toBe(true);
    expect(preview.merges[0]!).toEqual(expect.objectContaining({ range: 'A1:E3', rowSpan: 2, colSpan: 3, clipped: true }));
  });
});
