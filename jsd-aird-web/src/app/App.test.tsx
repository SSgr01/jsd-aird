import { render, screen } from '@testing-library/react';
import type * as ReactRouterDom from 'react-router-dom';

import { App } from '@/app/App';
import type * as RouteConfigModule from '@/routes/route-config';

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
  return { router: createMemoryRouter(routeConfig, { initialEntries: ['/templates/upload'] }) };
});

describe('App', () => {
  it('renders the platform shell', async () => {
    render(<App />);

    expect(await screen.findByText('模板上传')).toBeInTheDocument();
    expect(screen.getByText('生产单管理')).toBeInTheDocument();
  });
});
