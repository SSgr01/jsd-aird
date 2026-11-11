import { render, screen, within, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ResearchBriefings } from './ResearchBriefings';
import { getBriefings, type BriefingCard } from '@/services/agents/briefings-api';
import { useAuthStore } from '@/stores/auth-store';
import { queryClient } from '@/services/query/query-client';
import { businessKey } from '@/services/query/use-business-query';
import { testUser } from '@/test/auth-user';

vi.mock('@/services/agents/briefings-api', () => ({ getBriefings: vi.fn() }));
const user = { ...testUser, permissions: [...testUser.permissions, 'ai.use'] };
const makeCard = (kind: BriefingCard['kind'] = 'project-morning'): BriefingCard => ({
  kind, title: '项目晨报', state: 'READY', task_status: 'active', latest: null,
  result: {
    run_id: 'run-one', session_id: 'briefing-one', turn: 2, generated_at: '2026-11-11T01:03:00Z',
    window: { start: null, end: '2026-11-11T01:00:00Z', time_zone: 'Asia/Shanghai' },
    headline: '先确认测试条件', summary: '补齐固化条件后再比较结果。',
    actions: [{ text: '确认固化条件', href: '/projects/project-one' }],
    href: `/agent/?briefing=${kind}&run=run-one`,
  },
});
const mount = () => render(<MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><ResearchBriefings /></MemoryRouter>);

describe('homepage briefing navigation and states', () => {
  beforeEach(() => {
    queryClient.clear();
    vi.mocked(getBriefings).mockReset();
    useAuthStore.setState({ user, status: 'authenticated' });
    vi.spyOn(useAuthStore.getState(), 'validate').mockResolvedValue(user);
  });
  afterEach(() => vi.restoreAllMocks());

  it('shows real excerpts and concise labels linking to existing sessions', async () => {
    vi.mocked(getBriefings).mockResolvedValue({ items: [makeCard(), makeCard('research-weekly'), makeCard('experiment-review')] });
    mount();
    const morning = await screen.findByRole('link', { name: '查看晨报' });
    expect(morning).toHaveAttribute('href', '/agent/?briefing=project-morning&run=run-one');
    expect(screen.getByRole('link', { name: '查看周报' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '查看复盘' })).toBeInTheDocument();
    expect(within(screen.getByRole('article', { name: '项目晨报' })).getByRole('link', { name: /查看记录/ })).toHaveAttribute('href', '/projects/project-one');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('keeps a failed current period distinct from the previous successful excerpt', async () => {
    vi.mocked(getBriefings).mockResolvedValue({ items: [{ ...makeCard(), state: 'FAILED' }] });
    mount();
    expect(await screen.findByText('本期未完成 · 以下为最近一次完成的结果')).toBeInTheDocument();
    expect(screen.getByText('先确认测试条件')).toBeInTheDocument();
    expect(screen.getByText('11/11 09:03')).toBeInTheDocument();
  });

  it('does not fetch or display summaries without AI access', () => {
    useAuthStore.setState({ user: testUser });
    mount();
    expect(getBriefings).not.toHaveBeenCalled();
    expect(screen.queryByText('今日研判与行动')).not.toBeInTheDocument();
  });

  it('shows loading then a meaningful empty state without invented conclusions', async () => {
    let resolve!: (value: { items: BriefingCard[] }) => void;
    vi.mocked(getBriefings).mockImplementation(() => new Promise(done => { resolve = done; }));
    mount();
    expect(screen.getByRole('status', { name: '正在读取简报' })).toBeInTheDocument();
    await act(async () => { await Promise.resolve(); resolve({ items: [] }); });
    expect(await screen.findAllByText('尚未生成')).toHaveLength(3);
    expect(screen.queryByRole('link', { name: '查看晨报' })).not.toBeInTheDocument();
  });

  it('removes cached sensitive content when revalidation fails', async () => {
    vi.mocked(getBriefings).mockResolvedValue({ items: [makeCard()] });
    mount();
    expect(await screen.findByText('先确认测试条件')).toBeInTheDocument();
    vi.mocked(getBriefings).mockRejectedValue(new Error('permission changed'));
    await act(async () => { await queryClient.invalidateQueries({ queryKey: businessKey(user, 'research-briefings', {}) }); });
    expect(await screen.findByRole('alert')).toHaveTextContent('暂时无法读取简报');
    expect(screen.queryByText('先确认测试条件')).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '查看晨报' })).not.toBeInTheDocument();
  });
});
