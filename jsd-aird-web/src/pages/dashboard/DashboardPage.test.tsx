import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getProjects, getTasks } from '@/services/project/project-api';
import { inventoryApi } from '@/services/inventory/inventory-api';
import { DashboardPage } from './DashboardPage';
import { testUser } from '@/test/auth-user';
import { useAuthStore } from '@/stores/auth-store';
import { queryClient } from '@/services/query/query-client';

vi.mock('@/services/project/project-api', () => ({ getProjects: vi.fn(), getTasks: vi.fn() }));
vi.mock('@/services/experiments/experiment-api', () => ({
  listExperiments: vi.fn().mockResolvedValue({ items: [], total: 0 }),
}));
vi.mock('@/services/inventory/inventory-api', () => ({ inventoryApi: { balances: vi.fn() } }));
vi.mock('./DashboardQuickAction', () => ({ DashboardQuickAction: () => null }));

describe('dashboard independent module loading', () => {
  beforeEach(() => {
    queryClient.clear();
    useAuthStore.setState({ user: testUser, status: 'authenticated' });
    vi.mocked(getProjects).mockResolvedValue({
      items: [{ id: 'project-1', name: '已加载项目', projectCode: 'P-001' }], total: 1,
    } as Awaited<ReturnType<typeof getProjects>>);
    vi.mocked(getTasks).mockImplementation(() => new Promise(() => {}));
    vi.spyOn(inventoryApi, 'balances').mockImplementation(() => new Promise(() => {}));
  });

  function renderDashboard() {
    render(<MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><DashboardPage /></MemoryRouter>);
  }

  it('shows completed modules while other requests are still pending', async () => {
    renderDashboard();
    expect(await screen.findByText('已加载项目')).toBeInTheDocument();
  });

  it('keeps successful modules visible when another request fails', async () => {
    vi.spyOn(inventoryApi, 'balances').mockRejectedValueOnce(new Error('inventory unavailable'));
    renderDashboard();
    expect(await screen.findByText('已加载项目')).toBeInTheDocument();
  });
});
