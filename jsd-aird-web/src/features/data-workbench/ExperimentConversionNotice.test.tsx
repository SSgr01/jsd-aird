import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ExperimentConversionNotice } from './ExperimentConversionNotice';
import { dataApi } from '@/services/data/data-api';
vi.mock('@/services/data/data-api', () => ({ dataApi: { conversionForImport: vi.fn() } }));
vi.mock('@/services/files/file-api', () => ({ triggerNativeDownload: vi.fn() }));
describe('conversion provenance in human review', () => {
  beforeEach(() => vi.clearAllMocks());
  it('machine validated result stays pending and exposes unplaced values and original', async () => {
    vi.mocked(dataApi.conversionForImport).mockResolvedValue({ id: 'conversion', sourceName: '原件.xlsx', report: { status: 'validated', human_review: 'pending', unplaced: [{ value: 0, reason: '无对应时间轴' }], source_conflicts: [] } } as never);
    render(<ExperimentConversionNotice importId="import" confirmed={false} />);
    expect(await screen.findByText(/专家转换结果待人工核对/)).toBeTruthy();
    expect(screen.getByText('下载原始实验文件')).toBeTruthy();
    fireEvent.click(screen.getByText('查看未落位项与源数据冲突'));
    expect(screen.getByText(/无对应时间轴/)).toBeTruthy();
    expect(screen.queryByText('完成数据核对')).toBeNull();
  });
  it('a source fetch failure is visible and retryable', async () => {
    vi.mocked(dataApi.conversionForImport).mockRejectedValue(new Error('unavailable'));
    render(<ExperimentConversionNotice importId="import" confirmed={false} />);
    expect(await screen.findByText(/转换来源信息暂不可读/)).toBeTruthy();
    vi.mocked(dataApi.conversionForImport).mockResolvedValue(null);
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    await waitFor(() => expect(dataApi.conversionForImport).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
  });
});
