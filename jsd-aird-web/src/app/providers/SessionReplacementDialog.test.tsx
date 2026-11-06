import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';

import { SessionReplacementDialog } from '@/app/providers/SessionReplacementDialog';
import { confirmSessionReplacement, notifyAuthRequired } from '@/services/http/auth-events';
import { useAuthStore } from '@/stores/auth-store';

afterEach(() => {
  act(() => confirmSessionReplacement());
});

it('shows a blocking dialog and exits only after confirmation', async () => {
  useAuthStore.setState({ status: 'authenticated', user: null });
  render(<SessionReplacementDialog />);

  act(() => notifyAuthRequired('SESSION_REPLACED'));
  expect(await screen.findByRole('dialog')).toHaveTextContent('账号已在其他地方登录');
  expect(useAuthStore.getState().status).toBe('authenticated');

  fireEvent.click(screen.getByRole('button', { name: /确\s*定/ }));
  expect(useAuthStore.getState().status).toBe('anonymous');
});
