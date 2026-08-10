import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { UploadFile } from 'antd';

import { UploadWorkspace } from './UploadWorkspace';

describe('UploadWorkspace', () => {
  it('supports file selection, preview removal, filters, search and submit', () => {
    const onFilesChange = vi.fn();
    const onRemoveFile = vi.fn();
    const onClearFiles = vi.fn();
    const onFilterChange = vi.fn();
    const onSearchChange = vi.fn();
    const onSubmit = vi.fn();
    const file: UploadFile = { uid: 'file-1', name: '原料导入.xlsx', size: 2048, status: 'done' };

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
});
