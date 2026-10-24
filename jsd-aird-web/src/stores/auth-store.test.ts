import { beforeEach, describe, expect, it, vi } from 'vitest';

import { authApi } from '@/services/auth/auth-api';
import { HttpError } from '@/services/http/errors';
import { useAuthStore } from '@/stores/auth-store';
import { testUser } from '@/test/auth-user';
import { queryClient } from '@/services/query/query-client';

describe('auth store session loading', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    useAuthStore.setState({ status: 'unknown', user: null });
  });

  it('keeps service failures out of the anonymous state', async () => {
    vi.spyOn(authApi, 'me').mockRejectedValueOnce(new Error('network unavailable'));

    await useAuthStore.getState().load();

    expect(useAuthStore.getState().status).toBe('error');
  });

  it('marks the session anonymous for an authentication failure', async () => {
    vi.spyOn(authApi, 'me').mockRejectedValueOnce(new HttpError('请先登录', 'AUTH_REQUIRED', 401));

    await useAuthStore.getState().load();

    expect(useAuthStore.getState()).toMatchObject({ status: 'anonymous', user: null });
  });

  it('shares the pending session promise across startup consumers', async () => {
    let finish!: (user: typeof testUser) => void;
    const me = vi.spyOn(authApi, 'me').mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    const first = useAuthStore.getState().load();
    const second = useAuthStore.getState().load();
    expect(first).toBe(second);
    expect(me).toHaveBeenCalledTimes(1);
    finish(testUser);
    await expect(second).resolves.toEqual(testUser);
  });

  it('does not restore a session from a late bootstrap response after logout', async () => {
    let finish!: (user: typeof testUser) => void;
    vi.spyOn(authApi, 'me').mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    vi.spyOn(authApi, 'logout').mockResolvedValue();
    const loading = useAuthStore.getState().load();
    await useAuthStore.getState().logout();
    finish(testUser);
    await loading;
    expect(useAuthStore.getState()).toMatchObject({ status: 'anonymous', user: null });
  });

  it('clears business data when permissions change without a new user id', () => {
    useAuthStore.setState({ user: testUser, status: 'authenticated' });
    queryClient.setQueryData(['business','org-1','user-1',1,'projects',{}], ['cached']);
    useAuthStore.setState({ user: { ...testUser, permissions: [] } });
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
  });

  it('checks an active session once after 30 seconds without replacing the shell', async () => {
    vi.useFakeTimers();
    try {
      useAuthStore.setState({user:testUser,status:'authenticated'});
      let finish!: (user: typeof testUser) => void;
      const me=vi.spyOn(authApi,'me').mockImplementation(()=>new Promise(resolve=>{finish=resolve}));
      await useAuthStore.getState().validate(); expect(me).not.toHaveBeenCalled();
      vi.advanceTimersByTime(30_000);
      const first=useAuthStore.getState().validate(); const second=useAuthStore.getState().validate();
      expect(first).toBe(second); expect(me).toHaveBeenCalledOnce();
      expect(useAuthStore.getState().status).toBe('authenticated');
      finish(testUser); await first;
    } finally { vi.useRealTimers(); }
  });

  it('keeps the authenticated shell on a background network failure', async () => {
    useAuthStore.setState({user:testUser,status:'authenticated'});
    vi.spyOn(authApi,'me').mockRejectedValue(new HttpError('offline','NETWORK'));
    await useAuthStore.getState().load(true);
    expect(useAuthStore.getState()).toMatchObject({user:testUser,status:'authenticated'});
  });

  it('does not resurrect an identity when logout occurs during background validation', async () => {
    useAuthStore.setState({user:testUser,status:'authenticated'});
    let finish!: (user:typeof testUser)=>void;
    vi.spyOn(authApi,'me').mockImplementation(()=>new Promise(resolve=>{finish=resolve}));
    vi.spyOn(authApi,'logout').mockResolvedValue();
    const loading=useAuthStore.getState().load(true);
    await useAuthStore.getState().logout(); finish(testUser); await loading;
    expect(useAuthStore.getState()).toMatchObject({status:'anonymous',user:null});
  });

  it('ignores a late profile from before cross-tab identity synchronization', async () => {
    let finish!: (user:typeof testUser)=>void;
    vi.spyOn(authApi,'me').mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve})).mockResolvedValue({...testUser,userId:'other'});
    const old=useAuthStore.getState().load();
    await useAuthStore.getState().resync(); finish(testUser); await old;
    expect(useAuthStore.getState().user?.userId).toBe('other');
  });
});
