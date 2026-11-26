import type { ComponentProps } from 'react';
import { App } from 'antd';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { generateUUID } from '@/utils/uuid';
import type * as UuidModule from '@/utils/uuid';
import type { UploadWorkspace } from '@/components/upload-workspace';
import { ExperimentUploadPage } from './ExperimentUploadPage';

const api = vi.hoisted(() => ({
  categories: vi.fn(), imports: vi.fn(), stage: vi.fn(), templates: vi.fn(),
  jobs: vi.fn(), conversions: vi.fn(), create: vi.fn(), syncStatus: vi.fn(),
}));
vi.mock('@/services/experiments/experiment-api', () => ({
  listCategories: api.categories, listExperimentImports: api.imports, stageExperimentFile: api.stage,
}));
vi.mock('@/services/data/data-api', () => ({ dataApi: {
  listTemplates: api.templates, listExperimentJobs: api.jobs, conversions: api.conversions,
  createExperimentJob: api.create, experimentSyncStatus: api.syncStatus,
} }));
vi.mock('@/stores/auth-store', () => ({
  useAuthStore: (select: (state: { user: { permissions: string[] } }) => unknown) =>
    select({ user: { permissions: ['eln.upload.create', 'ai.use'] } }),
}));
vi.mock('@/utils/uuid', async importOriginal => {
  const original = await importOriginal<typeof UuidModule>();
  return { generateUUID: vi.fn(original.generateUUID) };
});
vi.mock('@/components/project-relations/ProjectRelationPicker', () => ({ ProjectRelationPicker: () => null }));
vi.mock('@/components/upload-workspace', () => ({
  UploadWorkspace: (props: ComponentProps<typeof UploadWorkspace>) => <div>
    <span>导入记录数：{props.rightCount}</span>
    {props.records?.map(record => <div key={record.id}><span>{record.status?.label}</span>{record.detail}{record.actions}</div>)}
    <button onClick={() => props.onFilesChange([{
      uid: 'file', name: '实验.xlsx', originFileObj: Object.assign(new File(['data'], '实验.xlsx'), {
        uid: 'file', lastModifiedDate: new Date(),
      }),
    }])}>选择文件</button>
    <button disabled={props.submitDisabled || props.submitting} onClick={props.onSubmit}>
      {props.submitting ? '上传中' : '开始上传'}
    </button>
  </div>,
}));

const cryptoApi = globalThis.crypto;
beforeEach(() => {
  vi.clearAllMocks();
  api.categories.mockResolvedValue([{ id: 'category', name: '实验分类' }]);
  api.templates.mockResolvedValue([{ versionId: 'template', format: 'XLSX', experimentImportReady: true }]);
  api.imports.mockResolvedValue([]);
  api.jobs.mockResolvedValue({ items: [], totalPages: 1 });
  api.conversions.mockResolvedValue([]);
  api.stage.mockResolvedValue({ fileId: 'staged' });
  api.create.mockResolvedValue({ id: 'job' });
  api.syncStatus.mockResolvedValue({ plan: null, links: [], preparationStatus: 'RUNNING' });
});
afterEach(() => vi.unstubAllGlobals());

async function prepare() {
  render(<App><MemoryRouter><ExperimentUploadPage /></MemoryRouter></App>);
  await waitFor(() => expect(screen.getByRole('button', { name: '开始上传' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '选择文件' }));
}

it('submits experimental files on LAN HTTP without crypto.randomUUID', async () => {
  vi.stubGlobal('crypto', { getRandomValues: cryptoApi.getRandomValues.bind(cryptoApi) });
  await prepare();
  fireEvent.click(screen.getByRole('button', { name: '开始上传' }));
  await waitFor(() => expect(api.create).toHaveBeenCalledWith(expect.objectContaining({
    sourceFileId: 'staged', templateVersionId: 'template', targetExperimentCategoryId: undefined,
  })));
  await waitFor(() => expect(screen.getByRole('button', { name: '开始上传' })).toBeEnabled());
});

it('reports initialization errors and releases the upload button for retry', async () => {
  vi.mocked(generateUUID).mockImplementationOnce(() => { throw new Error('无法创建请求编号'); });
  await prepare();
  fireEvent.click(screen.getByRole('button', { name: '开始上传' }));
  expect(await screen.findByText('无法创建请求编号')).toBeInTheDocument();
  expect(api.stage).not.toHaveBeenCalled();
  expect(screen.getByRole('button', { name: '开始上传' })).toBeEnabled();
  fireEvent.click(screen.getByRole('button', { name: '开始上传' }));
  await waitFor(() => expect(api.create).toHaveBeenCalledOnce());
});

it('keeps other import histories visible when one source fails', async () => {
  api.conversions.mockRejectedValue(new Error('转换记录暂不可用'));
  api.imports.mockResolvedValue([{ id: 'own-import', sourceFileName: '本人实验.xlsx', status: 'PARSING', createdAt: new Date().toISOString() }]);
  render(<App><MemoryRouter><ExperimentUploadPage /></MemoryRouter></App>);
  expect(await screen.findByText('导入记录数：1')).toBeInTheDocument();
  expect(await screen.findByText('部分导入记录加载失败，请刷新重试')).toBeInTheDocument();
});

it('keeps completed imports visible while their assembly plan is still preparing', async () => {
  api.jobs.mockResolvedValue({ items: [{ id: 'preparing', sourceFileName: 'UA-3160B.xlsx', status: 'COMPLETED', importPurpose: 'EXPERIMENT_DRAFT', createdAt: new Date().toISOString() }], totalPages: 1 });
  render(<App><MemoryRouter><ExperimentUploadPage /></MemoryRouter></App>);
  await waitFor(() => expect(api.syncStatus).toHaveBeenCalledWith('preparing'));
  expect(await screen.findByText('导入记录数：1')).toBeInTheDocument();
});

it('separates pending, accepted, running and failed launches', async () => {
  const createdAt = new Date().toISOString();
  api.conversions.mockResolvedValue([
    { id: 'pending', sourceName: '待提交.xlsx', status: 'QUEUED', createdAt },
    { id: 'accepted', sourceName: '已受理.xlsx', status: 'ACCEPTED', createdAt },
    { id: 'running', sourceName: '运行中.xlsx', status: 'RUNNING', createdAt },
    { id: 'failed', sourceName: '启动失败.xlsx', status: 'START_FAILED', createdAt, errorMessage: '绑定实验转换专家失败：权限不足' },
  ]);
  render(<App><MemoryRouter><ExperimentUploadPage /></MemoryRouter></App>);
  for (const label of ['未提交', '专家已受理', '运行中', '启动失败']) {
    expect(await screen.findByText(label)).toBeInTheDocument();
  }
  expect(screen.getByText(/绑定实验转换专家失败：权限不足/)).toBeInTheDocument();
  expect(screen.getAllByRole('button', { name: '启动转换' })).toHaveLength(1);
  expect(screen.getByRole('button', { name: '重试启动' })).toBeInTheDocument();
});
