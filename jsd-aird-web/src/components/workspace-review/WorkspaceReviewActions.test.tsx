import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { WorkspaceReviewActions } from './WorkspaceReviewActions';

const { api, permissions } = vi.hoisted(() => ({
  api: { get: vi.fn(), submit: vi.fn(), approve: vi.fn(), reject: vi.fn(), history: vi.fn() },
  permissions: new Set<string>(),
}));
vi.mock('@/services/reviews/workspace-review-api', () => ({ workspaceReviewApi: api }));
vi.mock('@/components/auth/usePermission', () => ({ usePermission: (code: string) => permissions.has(code) }));

describe('workspace review actions', () => {
  function deferred<T>() {
    let resolve!: (value: T) => void;
    const promise = new Promise<T>((done) => { resolve = done; });
    return { promise, resolve };
  }
  beforeEach(() => {
    vi.clearAllMocks();
    permissions.clear();
    api.get.mockResolvedValue({ status: 'NOT_SUBMITTED' });
    api.submit.mockResolvedValue({ status: 'SUBMITTED' });
    api.approve.mockResolvedValue({ status: 'APPROVED' });
    api.reject.mockResolvedValue({ status: 'REJECTED', reviewComment: '数据需修正' });
    api.history.mockResolvedValue([{ action: 'SUBMITTED', operatorName: '审核人', createdAt: '2026-11-04T00:00:00Z' }]);
  });

  it('requires separate submit and approve permissions and advances the review state', async () => {
    permissions.add('quality.view.submit');
    const view = render(<WorkspaceReviewActions type="QUALITY_RECORD" id="record-1" saved />);
    expect(await screen.findByRole('button', { name: '提交审核' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: '审核通过' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '提交审核' }));
    await waitFor(() => expect(api.submit).toHaveBeenCalledWith('QUALITY_RECORD', 'record-1'));
    expect(screen.queryByRole('button', { name: '提交审核' })).not.toBeInTheDocument();

    permissions.add('quality.view.approve');
    view.rerender(<WorkspaceReviewActions type="QUALITY_RECORD" id="record-1" saved />);
    fireEvent.click(await screen.findByRole('button', { name: '审核通过' }));
    await waitFor(() => expect(api.approve).toHaveBeenCalledWith('QUALITY_RECORD', 'record-1'));
    expect(screen.queryByRole('button', { name: '审核通过' })).not.toBeInTheDocument();
  });

  it('shows quality workflow states as draft through publication', async () => {
    permissions.add('quality.view.submit');
    const view = render(<WorkspaceReviewActions type="QUALITY_RECORD" id="record-1" saved />);
    expect(await screen.findByText('草稿')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '提交审核' }));
    expect(await screen.findByText('待审核')).toBeInTheDocument();
    view.rerender(<WorkspaceReviewActions type="QUALITY_RECORD" id="record-1" saved published />);
    expect(await screen.findByText('已发布')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '提交审核' })).not.toBeInTheDocument();
  });

  it('requires a reason to reject and allows resubmission', async () => {
    permissions.add('quality.view.approve');
    permissions.add('quality.view.submit');
    api.get.mockResolvedValue({ status: 'SUBMITTED' });
    render(<WorkspaceReviewActions type="QUALITY_RECORD" id="record-1" saved />);
    fireEvent.click(await screen.findByRole('button', { name: /驳\s*回/ }));
    const confirm = screen.getByRole('button', { name: '确认驳回' });
    expect(confirm).toBeDisabled();
    fireEvent.change(screen.getByRole('textbox', { name: '驳回原因' }), { target: { value: '数据需修正' } });
    fireEvent.click(screen.getByRole('button', { name: '确认驳回' }));
    await waitFor(() => expect(api.reject).toHaveBeenCalledWith('QUALITY_RECORD', 'record-1', '数据需修正'));
    expect(await screen.findByText('已退回')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '提交审核' })).toBeEnabled();
  });

  it('shows production upload review actions and history', async () => {
    permissions.add('production.view.approve');
    api.get.mockResolvedValue({ status: 'SUBMITTED' });
    render(<WorkspaceReviewActions type="PRODUCTION_UPLOAD" id="upload-1" saved />);
    expect(await screen.findByRole('button', { name: /驳\s*回/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '审核记录' }));
    await waitFor(() => expect(api.history).toHaveBeenCalledWith('PRODUCTION_UPLOAD', 'upload-1'));
    expect(await screen.findByText(/审核人/)).toBeInTheDocument();
  });

  it('shows knowledge version rejection and review history', async () => {
    permissions.add('knowledge.view.approve');
    api.get.mockResolvedValue({ status: 'SUBMITTED' });
    render(<WorkspaceReviewActions type="KNOWLEDGE_VERSION" id="version-1" saved />);
    expect(await screen.findByRole('button', { name: /驳\s*回/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '审核记录' }));
    await waitFor(() => expect(api.history).toHaveBeenCalledWith('KNOWLEDGE_VERSION', 'version-1'));
  });

  it.each(['QUALITY_RECORD', 'PRODUCTION_UPLOAD', 'KNOWLEDGE_VERSION', 'PROJECT_DOCUMENT', 'PRODUCTION_ORDER'] as const)(
    '%s ignores an old refresh arriving after approval', async (type) => {
      for (const prefix of ['quality.view', 'production.view', 'knowledge.view', 'project.document']) permissions.add(`${prefix}.approve`);
      const delayed = deferred<{ status: string }>();
      api.get.mockResolvedValueOnce({ status: 'SUBMITTED' }).mockReturnValueOnce(delayed.promise);
      const changed = vi.fn();
      const view = render(<WorkspaceReviewActions type={type} id="one" saved refreshKey={1} onStatusChange={changed} />);
      await screen.findByRole('button', { name: '审核通过' });
      view.rerender(<WorkspaceReviewActions type={type} id="one" saved refreshKey={2} onStatusChange={changed} />);
      await waitFor(() => expect(api.get).toHaveBeenCalledTimes(2));
      fireEvent.click(screen.getByRole('button', { name: '审核通过' }));
      await screen.findByText('审核通过', { selector: '.ant-tag' });
      await act(async () => delayed.resolve({ status: 'SUBMITTED' }));
      expect(screen.getByText('审核通过', { selector: '.ant-tag' })).toBeInTheDocument();
      expect(changed).toHaveBeenLastCalledWith('APPROVED');
    },
  );

  it('ignores an approval response after switching to another record', async () => {
    permissions.add('quality.view.approve');
    const delayed = deferred<{ status: string }>();
    api.get.mockResolvedValueOnce({ status: 'SUBMITTED' }).mockResolvedValue({ status: 'NOT_SUBMITTED' });
    api.approve.mockReturnValueOnce(delayed.promise);
    const view = render(<WorkspaceReviewActions type="QUALITY_RECORD" id="one" saved />);
    fireEvent.click(await screen.findByRole('button', { name: '审核通过' }));
    view.rerender(<WorkspaceReviewActions type="QUALITY_RECORD" id="two" saved />);
    await screen.findByText('草稿');
    await act(async () => delayed.resolve({ status: 'APPROVED' }));
    expect(screen.getByText('草稿')).toBeInTheDocument();
    expect(screen.queryByText('审核通过')).not.toBeInTheDocument();
  });

  it('keeps the current review state after a failed approval and allows retry', async () => {
    permissions.add('quality.view.approve');
    api.get.mockResolvedValue({ status: 'SUBMITTED' });
    api.approve.mockRejectedValueOnce(new Error('连接中断'));
    render(<WorkspaceReviewActions type="QUALITY_RECORD" id="one" saved />);
    fireEvent.click(await screen.findByRole('button', { name: '审核通过' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '审核通过' })).toBeEnabled());
    expect(screen.getByText('待审核')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '审核通过' }));
    await screen.findByText('审核通过', { selector: '.ant-tag' });
    expect(api.approve).toHaveBeenCalledTimes(2);
  });
});
