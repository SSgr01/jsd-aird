import { act, renderHook, waitFor } from '@testing-library/react';
import { businessKey, useBusinessQuery } from './use-business-query';
import { createQueryClient, queryClient, retryRead } from './query-client';
import { HttpError } from '@/services/http/errors';
import { testUser } from '@/test/auth-user';
import { useAuthStore } from '@/stores/auth-store';
import { notifyAuthRequired } from '@/services/http/auth-events';

describe('session scoped business query cache', () => {
  beforeEach(() => {
    queryClient.clear();
    useAuthStore.setState({ user: testUser, status: 'authenticated' });
  });
  afterEach(() => { queryClient.clear(); });

  it('shows cached rows immediately and retains them after background failure', async () => {
    queryClient.setQueryData(businessKey(testUser,'projects',{page:1}), ['cached']);
    const read = vi.fn().mockRejectedValue(new HttpError('offline','NETWORK'));
    queryClient.setDefaultOptions({ queries: { retry: false, refetchOnMount: 'always' } });
    const { result } = renderHook(() => useBusinessQuery('projects',{page:1},read));
    expect(result.current.data).toEqual(['cached']);
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data).toEqual(['cached']);
  });

  it('keeps previous pages but never rows from another filter', async () => {
    const read = vi.fn().mockResolvedValueOnce(['page-one']).mockImplementation(() => new Promise(() => {}));
    const { result, rerender } = renderHook(({page,keyword}) => useBusinessQuery('projects',{page,size:20,keyword},read,{keepPage:true}), {initialProps:{page:1,keyword:'A'}});
    await waitFor(() => expect(result.current.data).toEqual(['page-one']));
    rerender({page:2,keyword:'A'});
    expect(result.current.data).toEqual(['page-one']);
    expect(result.current.isPlaceholderData).toBe(true);
    rerender({page:1,keyword:'B'});
    expect(result.current.data).toBeUndefined();
  });

  it('aborts old requests and rejects their late result after a user switch', async () => {
    let finish!: (rows: string[]) => void;
    let signal!: AbortSignal;
    const read = vi.fn((current: AbortSignal) => { signal=current; return new Promise<string[]>(resolve => { finish=resolve; }); });
    const { result } = renderHook(() => useBusinessQuery('projects',{},read));
    await waitFor(() => expect(read).toHaveBeenCalledOnce());
    const oldFinish=finish; const oldSignal=signal;
    act(() => useAuthStore.setState({user:{...testUser,userId:'user-2'}}));
    expect(oldSignal.aborted).toBe(true);
    await act(async () => { oldFinish(['private-old-data']); await Promise.resolve(); });
    expect(result.current.data).toBeUndefined();
    expect(queryClient.getQueryData(businessKey(testUser,'projects',{}))).toBeUndefined();
  });

  it('clears all data and cancels requests on 401', () => {
    queryClient.setQueryData(businessKey(testUser,'projects',{}),['private']);
    act(() => notifyAuthRequired());
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
    expect(useAuthStore.getState().status).toBe('anonymous');
  });

  it('erases every variant of a forbidden resource, including cached rows', async () => {
    const client=createQueryClient();
    const old=businessKey(testUser,'projects',{page:1});
    client.setQueryData(old,['private']);
    await expect(client.fetchQuery({queryKey:businessKey(testUser,'projects',{page:2}),queryFn:()=>Promise.reject(new HttpError('forbidden','DENIED',403))})).rejects.toThrow('forbidden');
    expect(client.getQueryData(old)).toBeUndefined();
    client.clear();
  });

  it('retries only a network/5xx read once and uses the specified lifetimes', () => {
    expect(retryRead(0,new HttpError('network','NETWORK'))).toBe(true);
    expect(retryRead(1,new HttpError('server','ERROR',503))).toBe(false);
    expect(retryRead(0,new HttpError('denied','DENIED',403))).toBe(false);
    const client=createQueryClient();
    expect(client.getDefaultOptions().queries).toMatchObject({staleTime:30000,gcTime:600000});
    expect(client.getDefaultOptions().mutations?.retry).toBe(false);
  });

  it('does not erase readable lists when an auxiliary options query is forbidden', async () => {
    const client=createQueryClient();
    const list=businessKey(testUser,'projects',{page:1});
    client.setQueryData(list,['readable-project']);
    await expect(client.fetchQuery({queryKey:businessKey(testUser,'projects',{kind:'options'}),queryFn:()=>Promise.reject(new HttpError('forbidden','DENIED',403))})).rejects.toThrow('forbidden');
    expect(client.getQueryData(list)).toEqual(['readable-project']);
    client.clear();
  });

  it('erases active cached rows even if permissions change before authVersion is incremented', async () => {
    const read=vi.fn().mockResolvedValueOnce(['private']).mockRejectedValue(new HttpError('denied','DENIED',403));
    const {result}=renderHook(()=>useBusinessQuery('projects',{},read));
    await waitFor(()=>expect(result.current.data).toEqual(['private']));
    act(()=>useAuthStore.setState({user:{...testUser,permissions:[]}}));
    await waitFor(()=>expect(result.current.isError).toBe(true));
    expect(result.current.data).toBeUndefined();
    expect(queryClient.getQueryData(businessKey(testUser,'projects',{}))).toBeUndefined();
  });
});
