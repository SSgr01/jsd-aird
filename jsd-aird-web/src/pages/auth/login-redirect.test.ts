import { describe, expect, it } from 'vitest';
import { loginDestination } from './login-redirect';

describe('login destination', () => {
  it('does not return to the password form after the session is revoked', () => {
    expect(loginDestination('/change-password')).toBe('/dashboard');
    expect(loginDestination('/change-password?source=settings')).toBe('/dashboard');
  });

  it('preserves other requested destinations', () => {
    expect(loginDestination('/projects/list?page=2')).toBe('/projects/list?page=2');
    expect(loginDestination()).toBe('/dashboard');
  });
});
