import { downloadBlob } from '@/services/files';
import { detectPreviewMode, downloadPreviewFile } from './file-preview-utils';

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
});
