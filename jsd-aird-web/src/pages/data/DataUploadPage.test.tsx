import { App } from 'antd';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import { dataApi } from '@/services/data/data-api';
import { DataUploadPage } from './DataUploadPage';

vi.mock('@/services/data/data-api', () => ({
  dataApi: {
    listTemplates: vi.fn(), listJobs: vi.fn(), stageSource: vi.fn(), createJob: vi.fn(),
    createRecognitionJob: vi.fn(), reprocessJob: vi.fn(), retryRecognitionJob: vi.fn(), hideJob: vi.fn(),
  },
}));

vi.mock('@/services/experiments/experiment-api', () => ({
  listCategories: vi.fn().mockResolvedValue([]),
}));

vi.mock('@/services/files', () => ({
  downloadFile: vi.fn(),
}));

vi.mock('@/services/project/project-resource-api', () => ({}));

vi.mock('@/components/project-relations/ProjectRelationPicker', () => ({
  ProjectRelationPicker: () => <div />,
}));

vi.mock('@/components/upload-workspace', () => ({
  UploadWorkspace: ({ classification, onFilesChange, onSubmit, submitDisabled }: {
    classification: React.ReactNode;
    onFilesChange: (files: Array<Record<string, unknown>>) => void;
    onSubmit: () => void;
    submitDisabled?: boolean;
  }) => <div>
    {classification}
    <button type="button" onClick={() => onFilesChange([{
      uid: 'word-file', name: 'report.docx', status: 'done',
      originFileObj: new File(['report'], 'report.docx', { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' }),
    }])}>添加 Word 文件</button>
    <button type="button" disabled={submitDisabled} onClick={onSubmit}>创建导入任务</button>
  </div>,
}));

const listTemplatesMock = vi.spyOn(dataApi, 'listTemplates');
const listJobsMock = vi.spyOn(dataApi, 'listJobs');
const stageSourceMock = vi.spyOn(dataApi, 'stageSource');
const createJobMock = vi.spyOn(dataApi, 'createJob');
const createRecognitionJobMock = vi.spyOn(dataApi, 'createRecognitionJob');

describe('DataUploadPage Word template flow', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listTemplatesMock.mockResolvedValue([
      { templateId: 'sheet-template', versionId: 'sheet-v1', templateCode: 'SHEET', name: '表格模板', versionNo: 1, format: 'XLSX' },
      { templateId: 'word-template', versionId: 'word-v1', templateCode: 'WORD', name: '报告模板', versionNo: 1, format: 'DOCX' },
    ]);
    listJobsMock.mockResolvedValue({ items: [], page: 1, size: 8, total: 0, totalPages: 0 });
    stageSourceMock.mockResolvedValue({ fileId: 'source-file', sha256: 'sha', status: 'READY' });
    createJobMock.mockResolvedValue({
      id: 'import-job', sourceFileId: 'source-file', sourceSha256: 'sha', sourceFileName: 'report.docx',
      sourceFormat: 'DOCX', templateVersionId: 'word-v1', status: 'QUEUED', progress: 0,
      createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
    });
    createRecognitionJobMock.mockResolvedValue({ id: 'recognition-job' } as never);
  });

  it('uses a selected published Word template for a DOCX import', async () => {
    render(<App><MemoryRouter><DataUploadPage /></MemoryRouter></App>);

    const selector = await screen.findByText('选择 Word 模板；不选时使用自由识别');
    fireEvent.mouseDown(selector);
    fireEvent.click(await screen.findByText('报告模板 · WORD · V1 · 普通数据'));
    fireEvent.click(screen.getByRole('button', { name: '添加 Word 文件' }));
    fireEvent.click(screen.getByRole('button', { name: '创建导入任务' }));

    await waitFor(() => expect(createJobMock).toHaveBeenCalledWith(expect.objectContaining({
      sourceFileId: 'source-file', templateVersionId: 'word-v1', importPurpose: 'DATA_ONLY',
    })));
    expect(createRecognitionJobMock).not.toHaveBeenCalled();
  });

  it('keeps free recognition when no Word template is selected', async () => {
    render(<App><MemoryRouter><DataUploadPage /></MemoryRouter></App>);

    await screen.findByText('选择 Word 模板；不选时使用自由识别');
    fireEvent.click(screen.getByRole('button', { name: '添加 Word 文件' }));
    fireEvent.click(screen.getByRole('button', { name: '创建导入任务' }));

    await waitFor(() => expect(createRecognitionJobMock).toHaveBeenCalledWith(expect.objectContaining({
      sourceFileId: 'source-file', sourceFormat: 'DOCX', recognitionMode: 'FREEFORM', importPurpose: 'DATA_ONLY',
    })));
    expect(createJobMock).not.toHaveBeenCalled();
  });
});
