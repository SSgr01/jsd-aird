import { render, screen } from '@testing-library/react';

import { App } from '@/app/App';

vi.mock('@/services/templates/template-api', () => ({
  templateApi: { listImports: vi.fn().mockResolvedValue([]) },
}));

describe('App', () => {
  it('renders the platform shell', async () => {
    render(<App />);

    expect(await screen.findByText('模板上传')).toBeInTheDocument();
    expect(screen.getByText('生产单管理')).toBeInTheDocument();
  });
});
