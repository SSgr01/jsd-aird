import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { UploadFile } from 'antd';

import { UploadWorkspace } from './UploadWorkspace';
import { useAuthStore } from '@/stores/auth-store';

describe('UploadWorkspace', () => {
  afterEach(() => act(() => useAuthStore.setState({ user: null })));

  it('shows only granted record actions and hides an unauthorized submit', () => {
    useAuthStore.setState({ user: {
      userId: 'user-1', organizationId: 'org-1', organizationName: 'Test',
      username: 'reader', displayName: 'Reader', status: 'ACTIVE', authVersion: 1,
      permissions: ['data.upload.download'],
    } });
    render(<UploadWorkspace
      breadcrumbs={[]} title="数据上传" leftTitle="导入" classification={null}
      accept=".csv" files={[]} onFilesChange={vi.fn()} onRemoveFile={vi.fn()}
      onClearFiles={vi.fn()} submitLabel="开始上传" onSubmit={vi.fn()}
      showSubmit={false} uploadDisabled rightTitle="历史"
      records={[{ id: 'job-1', name: 'source.csv', commonActions: {
        download: { permission: 'data.upload.download', onClick: vi.fn() },
        retry: { permission: 'data.upload.retry', onClick: vi.fn() },
        delete: { permission: 'data.upload.delete', onClick: vi.fn() },
      } }]}
    />);
    expect(screen.getByRole('button', { name: /下载/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /重试/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /删除/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '开始上传' })).not.toBeInTheDocument();
  });

  it('keeps the upload history but hides the drop zone for a read-only viewer', () => {
    render(<UploadWorkspace
      breadcrumbs={[]} title="数据上传" leftTitle="导入" classification={null}
      accept=".csv" files={[]} onFilesChange={vi.fn()} onRemoveFile={vi.fn()}
      onClearFiles={vi.fn()} submitLabel="开始上传" onSubmit={vi.fn()}
      showSubmit={false} uploadDisabled uploadReadOnly rightTitle="历史"
      records={[{ id: 'job-1', name: '历史记录.xlsx' }]}
    />);

    expect(document.querySelector('input[type="file"]')).toBeNull();
    expect(screen.queryByText('拖拽文件到此处/点击选择文件/点击拍照上传')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '开始上传' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /清\s*空/ })).not.toBeInTheDocument();
    expect(screen.getByText(/只有查看权限/)).toBeInTheDocument();
    expect(screen.getByText('历史记录.xlsx')).toBeInTheDocument();
  });

  it('supports file selection, preview removal, filters, search and submit', () => {
    const onFilesChange = vi.fn();
    const onRemoveFile = vi.fn();
    const onClearFiles = vi.fn();
    const onFilterChange = vi.fn();
    const onSearchChange = vi.fn();
    const onSubmit = vi.fn();
    const file: UploadFile = { uid: 'file-1', name: '原料导入.xlsx', size: 2048, status: 'uploading', percent: 42 };

    render(
      <UploadWorkspace
        breadcrumbs={[{ title: '数据中心' }, { title: '数据上传' }]}
        title="数据上传"
        leftTitle="数据分类"
        classification={<div>物料/原料</div>}
        accept=".xlsx"
        files={[file]}
        onFilesChange={onFilesChange}
        onRemoveFile={onRemoveFile}
        onClearFiles={onClearFiles}
        submitLabel="开始上传"
        onSubmit={onSubmit}
        rightTitle="已上传数据"
        rightFilters={[{ key: 'ALL', label: '全部' }, { key: 'PARSING', label: '解析中' }]}
        activeFilter="ALL"
        onFilterChange={onFilterChange}
        searchValue=""
        onSearchChange={onSearchChange}
        records={[{ id: 'job-1', name: '原料导入.xlsx', detail: '等待解析' }]}
      />,
    );

    expect(screen.getAllByText('原料导入.xlsx')).toHaveLength(2);
    expect(document.querySelector('.upload-workspace-preview-heading .upload-workspace-section-title'))
      .toHaveTextContent('文件预览区 (1)');
    expect(document.querySelector('.upload-workspace-right-heading .upload-workspace-section-title'))
      .toHaveTextContent('已上传数据 (1)');
    expect(screen.getByText('上传进度')).toBeInTheDocument();
    expect(screen.getByText('42%')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '移除 原料导入.xlsx' }));
    fireEvent.click(screen.getByRole('button', { name: /清\s*空/ }));
    fireEvent.click(screen.getByRole('tab', { name: '解析中' }));
    fireEvent.change(screen.getByRole('textbox', { name: '搜索文件名称' }), { target: { value: '原料' } });
    fireEvent.click(screen.getByRole('button', { name: '开始上传' }));

    expect(onRemoveFile).toHaveBeenCalledWith(file);
    expect(onClearFiles).toHaveBeenCalledTimes(1);
    expect(onFilterChange).toHaveBeenCalledWith('PARSING');
    expect(onSearchChange).toHaveBeenCalledWith('原料');
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('forwards a selected file from the dragger input', async () => {
    const onFilesChange = vi.fn();
    const selectedFile = new File(['name,value'], 'data.csv', { type: 'text/csv' });

    render(
      <UploadWorkspace
        breadcrumbs={[{ title: '数据中心' }]}
        title="数据上传"
        leftTitle="数据分类"
        classification={<div>物料/原料</div>}
        accept=".csv"
        files={[]}
        onFilesChange={onFilesChange}
        onRemoveFile={vi.fn()}
        onClearFiles={vi.fn()}
        submitLabel="开始上传"
        onSubmit={vi.fn()}
        rightTitle="已上传数据"
        records={[]}
      />,
    );

    const input = document.querySelector('input[type="file"]');
    expect(input).toBeTruthy();
    fireEvent.change(input as HTMLInputElement, { target: { files: [selectedFile] } });

    await waitFor(() => expect(onFilesChange).toHaveBeenCalled());
    const firstCall = onFilesChange.mock.calls[0] as unknown[] | undefined;
    const selectedFiles = firstCall?.[0] as UploadFile[] | undefined;
    expect(selectedFiles?.[0]?.name).toBe('data.csv');
  });

  it('keeps existing upload records steady during background refreshes', () => {
    render(
      <UploadWorkspace
        breadcrumbs={[{ title: '数据中心' }]}
        title="数据上传"
        leftTitle="数据分类"
        classification={<div>物料/原料</div>}
        accept=".csv"
        files={[]}
        onFilesChange={vi.fn()}
        onRemoveFile={vi.fn()}
        onClearFiles={vi.fn()}
        submitLabel="开始上传"
        onSubmit={vi.fn()}
        rightTitle="已上传数据"
        recordsLoading
        records={[{ id: 'job-1', name: '持续显示.xlsx', detail: '仍在解析' }]}
      />,
    );

    expect(screen.getByText('持续显示.xlsx')).toBeInTheDocument();
    expect(screen.queryByText('正在刷新任务状态…')).not.toBeInTheDocument();
  });
});
