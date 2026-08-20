import { act, render, screen } from '@testing-library/react';
import type * as ReactRouterDom from 'react-router-dom';

import { App } from '@/app/App';
import type * as RouteConfigModule from '@/routes/route-config';
import { useAuthStore } from '@/stores/auth-store';

vi.mock('@/services/templates/template-api', () => ({
  templateApi: { listImports: vi.fn().mockResolvedValue([]) },
}));

vi.mock('@/routes/router', async () => {
  const { createMemoryRouter } = await vi.importActual<typeof ReactRouterDom>(
    'react-router-dom',
  );
  const { routeConfig } = await vi.importActual<typeof RouteConfigModule>(
    '@/routes/route-config',
  );
  return {
    router: createMemoryRouter(routeConfig, {
      initialEntries: ['/templates/upload'],
      future: { v7_relativeSplatPath: true },
    }),
  };
});

describe('App', () => {
  it('renders the platform shell', async () => {
    useAuthStore.setState({
      status: 'authenticated',
      user: {
        userId: 'admin', organizationId: 'org', organizationName: '测试组织', username: 'admin',
        displayName: '系统管理员', email: 'admin@example.com', status: 'ACTIVE', authVersion: 1,
        permissions: [
          'ai.use', 'template.view', 'template.upload', 'project.view', 'knowledge.view', 'knowledge.review',
          'experiment.view', 'production.view', 'data.view', 'spectrum.view', 'customer.view',
          'system.user.view', 'system.permission.manage', 'system.audit.view',
        ],
      },
    });
    render(<App />);

    const asyncRenderOptions = { timeout: 5000 };
    expect((await screen.findAllByText('模板上传', undefined, asyncRenderOptions)).length).toBeGreaterThan(0);
    expect(await screen.findByText('生产单管理', undefined, asyncRenderOptions)).toBeInTheDocument();
    // ProLayout schedules a short tooltip state update when the shell mounts.
    // Let that update settle before the test environment tears down its window.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 500));
    });
  }, 15000);
});
