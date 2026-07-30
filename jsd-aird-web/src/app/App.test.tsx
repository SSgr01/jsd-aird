import { render, screen } from '@testing-library/react';

import { App } from '@/app/App';

vi.mock('@/services/health/health-api', () => ({
  getHealth: vi.fn().mockResolvedValue({ status: 'UP' }),
}));

describe('App', () => {
  it('renders the platform shell', async () => {
    render(<App />);

    expect(await screen.findByText('研发数字化与 AI 平台')).toBeInTheDocument();
  });
});
