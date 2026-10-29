import { create } from 'zustand';
import { replaceEqualDeep } from '@tanstack/react-query';

import { authApi, type AuthUser, type LoginRequest } from '@/services/auth/auth-api';
import { advanceSessionEpoch, subscribeAuthRequired } from '@/services/http/auth-events';
import { HttpError } from '@/services/http/errors';
import { clearSessionQueries } from '@/services/query/query-client';
import { clearSessionViewState } from '@/services/query/session-view-state';
import { publishSessionChange } from '@/services/query/query-sync';

let pendingLoad: Promise<AuthUser | null> | undefined;
let sessionGeneration = 0;
let lastValidatedAt = 0;

interface AuthState {
  user: AuthUser | null;
  status: 'unknown' | 'loading' | 'authenticated' | 'anonymous' | 'error';
  load: (background?: boolean) => Promise<AuthUser | null>;
  validate: () => Promise<AuthUser | null>;
  resync: () => Promise<AuthUser | null>;
  login: (request: LoginRequest) => Promise<AuthUser>;
  logout: () => Promise<void>;
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>;
  can: (permission: string) => boolean;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  status: 'unknown',
  load: (background = false) => {
    if (pendingLoad) return pendingLoad;
    const generation = sessionGeneration;
    const keepShell = background && get().status === 'authenticated';
    if (!keepShell) set({ status: 'loading' });
    const request = (async () => {
      try {
        const user = await authApi.me();
        if (generation !== sessionGeneration) return null;
        lastValidatedAt = Date.now();
        // Repeated /me JSON has new object/array references. Keep equal profile
        // references so a background validation does not redraw every subscriber.
        const sharedUser = replaceEqualDeep(get().user, user);
        set({ user: sharedUser, status: 'authenticated' });
        return sharedUser;
      } catch (error) {
        if (generation !== sessionGeneration) return null;
        if (!keepShell || (error instanceof HttpError && error.status === 401)) set({ user: null, status: error instanceof HttpError && error.status === 401 ? 'anonymous' : 'error' });
        return null;
      } finally {
        if (generation === sessionGeneration) pendingLoad = undefined;
      }
    })();
    pendingLoad = request;
    return request;
  },
  validate: () => {
    if (get().status !== 'authenticated' || !get().user) return Promise.resolve(null);
    return Date.now() - lastValidatedAt < 30_000 ? Promise.resolve(get().user) : get().load(true);
  },
  resync: () => {
    ++sessionGeneration;
    pendingLoad = undefined;
    lastValidatedAt = 0;
    advanceSessionEpoch();
    clearSessionQueries();
    // A shared-cookie notification may be a same-user login/password change.
    // Check first; only a real identity/permission change releases workspace state.
    return get().load(true);
  },
  login: async (request) => {
    const generation = ++sessionGeneration;
    pendingLoad = undefined;
    clearSessionQueries();
    const user = await authApi.login(request);
    if (generation !== sessionGeneration) throw new Error('登录状态已变更，请重新登录');
    set({ user, status: 'authenticated' });
    lastValidatedAt = Date.now();
    publishSessionChange('changed');
    return user;
  },
  logout: async () => {
    const generation = ++sessionGeneration;
    pendingLoad = undefined;
    publishSessionChange('ended');
    set({ user: null, status: 'anonymous' });
    clearSessionQueries();
    try {
      await authApi.logout();
    } finally {
      if (generation === sessionGeneration) set({ user: null, status: 'anonymous' });
    }
  },
  changePassword: async (currentPassword, newPassword) => {
    await authApi.changePassword({ currentPassword, newPassword });
    const user = await authApi.me();
    set({ user, status: 'authenticated' });
    lastValidatedAt = Date.now();
    publishSessionChange('changed');
  },
  can: (permission) => get().user?.permissions.includes(permission) ?? false,
}));

subscribeAuthRequired(() => {
  ++sessionGeneration;
  pendingLoad = undefined;
  clearSessionQueries();
  useAuthStore.setState({ user: null, status: 'anonymous' });
});

const identity = (user: AuthUser | null) => user ? JSON.stringify([user.organizationId, user.userId, user.authVersion, [...user.permissions].sort(), [...(user.menuKeys ?? [])].sort()]) : '';
useAuthStore.subscribe((state, previous) => {
  if (identity(state.user) !== identity(previous.user)) { lastValidatedAt=Date.now(); advanceSessionEpoch(); clearSessionQueries(); clearSessionViewState(); }
});
