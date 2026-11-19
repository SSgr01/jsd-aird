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
  jobs: vi.fn(), conversions: vi.fn(), create: vi.fn(),
}));
vi.mock('@/services/experiments/experiment-api', () => ({
  listCategories: api.categories, listExperimentImports: api.imports, stageExperimentFile: api.stage,
}));
vi.mock('@/services/data/data-api', () => ({ dataApi: {
  listTemplates: api.templates, listExperimentJobs: api.jobs, conversions: api.conversions,
  createExperimentJob: api.create,
} }));
vi.mock('@/stores/auth-store', () => ({
  useAuthStore: (select: (state: { user: { permissions: string[] } }) => unknown) =>
    select({ user: { permissions: ['eln.upload.create'] } }),
}));
vi.mock('@/utils/uuid', async importOriginal => {
  const original = await importOriginal<typeof UuidModule>();
  return { generateUUID: vi.fn(original.generateUUID) };
});
vi.mock('@/components/project-relations/ProjectRelationPicker', () => ({ ProjectRelationPicker: () => null }));
vi.mock('@/components/upload-workspace', () => ({
  UploadWorkspace: (props: ComponentProps<typeof UploadWorkspace>) => <div>
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
    sourceFileId: 'staged', templateVersionId: 'template', targetExperimentCategoryId: 'category',
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
