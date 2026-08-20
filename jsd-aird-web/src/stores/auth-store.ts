import { create } from 'zustand';

import { authApi, type AuthUser, type LoginRequest } from '@/services/auth/auth-api';
import { HttpError } from '@/services/http/errors';

interface AuthState {
  user: AuthUser | null;
  status: 'unknown' | 'loading' | 'authenticated' | 'anonymous';
  load: () => Promise<AuthUser | null>;
  login: (request: LoginRequest) => Promise<AuthUser>;
  logout: () => Promise<void>;
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>;
  can: (permission: string) => boolean;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  status: 'unknown',
  load: async () => {
    if (get().status === 'loading') return get().user;
    set({ status: 'loading' });
    try {
      const user = await authApi.me();
      set({ user, status: 'authenticated' });
      return user;
    } catch (error) {
      if (error instanceof HttpError && error.status === 401) {
        set({ user: null, status: 'anonymous' });
        return null;
      }
      set({ status: 'anonymous', user: null });
      return null;
    }
  },
  login: async (request) => {
    const user = await authApi.login(request);
    set({ user, status: 'authenticated' });
    return user;
  },
  logout: async () => {
    try {
      await authApi.logout();
    } finally {
      set({ user: null, status: 'anonymous' });
    }
  },
  changePassword: async (currentPassword, newPassword) => {
    await authApi.changePassword({ currentPassword, newPassword });
    const user = await authApi.me();
    set({ user, status: 'authenticated' });
  },
  can: (permission) => get().user?.permissions.includes(permission) ?? false,
}));
