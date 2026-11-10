import { fireEvent, render, screen } from '@testing-library/react';
import { App } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it } from 'vitest';

import { FirstLoginPasswordDialog } from './FirstLoginPasswordDialog';
import { useAuthStore } from '@/stores/auth-store';
import { testUser } from '@/test/auth-user';

afterEach(() => {
  useAuthStore.setState({ user: null, status: 'anonymous', firstLoginPrompt: false });
});

it('shows the first-login password form and allows dismissal', async () => {
  useAuthStore.setState({ user: testUser, status: 'authenticated', firstLoginPrompt: true });
  render(<MemoryRouter><App><FirstLoginPasswordDialog /></App></MemoryRouter>);

  expect(await screen.findByRole('dialog')).toHaveTextContent('首次登录，请修改密码');
  expect(screen.getByLabelText('当前密码')).toBeInTheDocument();
  expect(screen.getByLabelText('新密码')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '稍后再说' }));
  expect(useAuthStore.getState().firstLoginPrompt).toBe(false);
});
