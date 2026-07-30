import { render, screen } from '@testing-library/react';

import { HomePage } from '@/pages/home/HomePage';

vi.mock('@/services/health/health-api', () => ({
  getHealth: vi.fn().mockResolvedValue({ status: 'UP' }),
}));

describe('HomePage', () => {
  it('describes the scaffold boundary', async () => {
    render(<HomePage />);

    expect(screen.getByText('基础工程')).toBeInTheDocument();
    expect(screen.getByText(/尚未实现具体业务功能/)).toBeInTheDocument();
    expect(await screen.findByText('后端服务正常')).toBeInTheDocument();
  });
});
