import { App } from 'antd';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import { dataApi } from '@/services/data/data-api';
import { useAuthStore } from '@/stores/auth-store';
import { DataUploadPage } from './DataUploadPage';

vi.mock('@/services/data/data-api', () => ({
  dataApi: {
    listTemplates: vi.fn(), listJobs: vi.fn(), stageSource: vi.fn(), createJob: vi.fn(),
    reprocessJob: vi.fn(), hideJob: vi.fn(),
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
  UploadWorkspace: ({ classification, onFilesChange, onSubmit, submitDisabled, uploadReadOnly, showSubmit }: {
    classification: React.ReactNode;
    onFilesChange: (files: Array<Record<string, unknown>>) => void;
    onSubmit: () => void;
    submitDisabled?: boolean;
    uploadReadOnly?: boolean;
    showSubmit?: boolean;
  }) => <div>
    {classification}
    {uploadReadOnly ? <span>当前账号只有查看权限</span> : <button type="button" onClick={() => onFilesChange([{
      uid: 'word-file', name: 'report.docx', status: 'done',
      originFileObj: new File(['report'], 'report.docx', { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' }),
    }])}>添加 Word 文件</button>}
    {showSubmit && <button type="button" disabled={submitDisabled} onClick={onSubmit}>创建导入任务</button>}
  </div>,
}));

const listTemplatesMock = vi.spyOn(dataApi, 'listTemplates');
const listJobsMock = vi.spyOn(dataApi, 'listJobs');
const stageSourceMock = vi.spyOn(dataApi, 'stageSource');
const createJobMock = vi.spyOn(dataApi, 'createJob');

describe('DataUploadPage Word template flow', () => {
  beforeEach(() => {
    // The submit guard requires the import grant; the page hides the submit
    // button for read-only visitors.
    useAuthStore.setState({ user: {
      userId: 'user-1', organizationId: 'org-1', organizationName: 'Test',
      username: 'uploader', displayName: 'Uploader', status: 'ACTIVE', authVersion: 1,
      permissions: ['data.upload.create'],
    } });
    vi.clearAllMocks();
    listTemplatesMock.mockResolvedValue([
      { templateId: 'sheet-template', versionId: 'sheet-v1', templateCode: 'SHEET', name: '表格模板', versionNo: 1, format: 'XLSX' },
      { templateId: 'word-template', versionId: 'word-v1', templateCode: 'WORD', name: '报告模板', versionNo: 1, format: 'DOCX' },
    ]);
    listJobsMock.mockResolvedValue({ items: [], page: 1, size: 8, total: 0, totalPages: 0 });
    stageSourceMock.mockResolvedValue({ fileId: 'source-file', sha256: 'sha', status: 'READY' });
    createJobMock.mockResolvedValue({
      id: 'import-job', revision: 'a'.repeat(64), sourceFileId: 'source-file', sourceSha256: 'sha', sourceFileName: 'report.docx',
      sourceFormat: 'DOCX', templateVersionId: 'word-v1', status: 'QUEUED', progress: 0,
      createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
    });
  });

  it('uses a selected published Word template for a DOCX import', async () => {
    render(<App><MemoryRouter><DataUploadPage /></MemoryRouter></App>);

    const selector = await screen.findByText('请选择已发布的 Word 模板');
    fireEvent.mouseDown(selector);
    fireEvent.click(await screen.findByText('报告模板 · WORD · V1 · 普通数据'));
    fireEvent.click(screen.getByRole('button', { name: '添加 Word 文件' }));
    fireEvent.click(screen.getByRole('button', { name: '创建导入任务' }));

    await waitFor(() => expect(createJobMock).toHaveBeenCalledWith(expect.objectContaining({
      sourceFileId: 'source-file', templateVersionId: 'word-v1', importPurpose: 'DATA_ONLY',
    })));
  });

  it('does not allow a Word import without a published template', async () => {
    render(<App><MemoryRouter><DataUploadPage /></MemoryRouter></App>);

    await screen.findByText('请选择已发布的 Word 模板');
    fireEvent.click(screen.getByRole('button', { name: '添加 Word 文件' }));

    expect(screen.getByRole('button', { name: '创建导入任务' })).toBeDisabled();
    expect(createJobMock).not.toHaveBeenCalled();
  });

  it('keeps the upload workspace read-only without the upload operation', async () => {
    useAuthStore.setState((state) => ({ user: state.user && {
      ...state.user, permissions: ['data.view'],
    } }));
    render(<App><MemoryRouter><DataUploadPage /></MemoryRouter></App>);

    expect(await screen.findByText('当前账号只有查看权限')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '添加 Word 文件' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '创建导入任务' })).not.toBeInTheDocument();
    expect(stageSourceMock).not.toHaveBeenCalled();
  });

  it('allows uploads with the data center create operation', async () => {
    useAuthStore.setState((state) => ({ user: state.user && {
      ...state.user, permissions: ['data.view', 'data.view.create'],
    } }));
    render(<App><MemoryRouter><DataUploadPage /></MemoryRouter></App>);

    expect(await screen.findByRole('button', { name: '添加 Word 文件' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '创建导入任务' })).toBeInTheDocument();
    expect(screen.queryByText('当前账号只有查看权限')).not.toBeInTheDocument();
  });
});
