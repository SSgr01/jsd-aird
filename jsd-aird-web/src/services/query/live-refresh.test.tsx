import { act, renderHook } from '@testing-library/react';
import { focusManager, onlineManager } from '@tanstack/react-query';
import { useBusinessQuery, businessKey } from './use-business-query';
import { queryClient, createQueryClient } from './query-client';
import { useAuthStore } from '@/stores/auth-store';
import { authApi } from '@/services/auth/auth-api';
import { testUser } from '@/test/auth-user';
import { MemoryRouter } from 'react-router-dom';
import type { PropsWithChildren } from 'react';
import { useListUrl } from './use-list-url';
import { useListPageBounds } from './use-list-page-bounds';

const tick = async (ms=10) => { await act(async () => { await vi.advanceTimersByTimeAsync(ms); }); };
beforeEach(() => {
  vi.useFakeTimers();
  vi.spyOn(Math, 'random').mockReturnValue(0.99999);
  queryClient.clear(); queryClient.setDefaultOptions(createQueryClient().getDefaultOptions()); queryClient.mount();
  focusManager.setFocused(true); onlineManager.setOnline(true);
  useAuthStore.setState({user:null,status:'unknown'});
  useAuthStore.setState({user:testUser,status:'authenticated'});
  vi.spyOn(authApi,'me').mockResolvedValue(testUser);
});
afterEach(() => {
  queryClient.unmount(); queryClient.clear();
  focusManager.setFocused(undefined); onlineManager.setOnline(true);
  vi.restoreAllMocks(); vi.useRealTimers();
});

it('discovers another device update on the next visible 30-second check', async () => {
  let revision=1; const read=vi.fn(()=>Promise.resolve({revision}));
  const {result,unmount}=renderHook(()=>useBusinessQuery('projects',{},read));
  await tick(); expect(result.current.data).toEqual({revision:1});
  revision=2;
  await tick(29_000); expect(read).toHaveBeenCalledOnce();
  await tick(1_010); expect(result.current.data).toEqual({revision:2});
  expect(read).toHaveBeenCalledTimes(2); expect(authApi.me).toHaveBeenCalledOnce(); unmount();
});

it('suspends hidden-tab polling and refreshes stale data on return', async () => {
  const read=vi.fn().mockResolvedValueOnce('old').mockResolvedValue('new');
  const {result,unmount}=renderHook(()=>useBusinessQuery('projects',{},read));
  await tick(); act(()=>focusManager.setFocused(false));
  await tick(90_000); expect(read).toHaveBeenCalledOnce(); expect(result.current.data).toBe('old');
  act(()=>focusManager.setFocused(true)); await tick();
  expect(result.current.data).toBe('new'); expect(read).toHaveBeenCalledTimes(2); unmount();
});

it('keeps cached rows while offline and resumes with a fresh read on reconnect', async () => {
  const read=vi.fn().mockResolvedValueOnce('cached').mockResolvedValue('new');
  const {result,unmount}=renderHook(()=>useBusinessQuery('projects',{},read));
  await tick(); expect(result.current.isPaused).toBe(false);
  act(()=>onlineManager.setOnline(false)); await tick(30_010);
  expect(read).toHaveBeenCalledOnce(); expect(result.current.data).toBe('cached'); expect(result.current.isPaused).toBe(true);
  act(()=>onlineManager.setOnline(true)); await tick();
  expect(result.current.data).toBe('new'); expect(result.current.isPaused).toBe(false); expect(read).toHaveBeenCalledTimes(2); unmount();
});

it('polls displayed category counts while leaving ordinary dictionaries on a five-minute lifetime', async () => {
  const counts=vi.fn().mockResolvedValue(1); const dictionary=vi.fn().mockResolvedValue(['option']);
  const live=renderHook(()=>useBusinessQuery('data',{kind:'categories'},counts,{dictionary:true,live:true}));
  const picker=renderHook(()=>useBusinessQuery('data',{kind:'templates'},dictionary,{dictionary:true}));
  await tick(); await tick(30_010);
  expect(counts).toHaveBeenCalledTimes(2); expect(dictionary).toHaveBeenCalledOnce(); live.unmount(); picker.unmount();
});

it('checks shared session identity before fetching again and discards the previous identity cache', async () => {
  const other={...testUser,userId:'other'};
  const read=vi.fn(()=>Promise.resolve(useAuthStore.getState().user?.userId));
  const {result,unmount}=renderHook(()=>useBusinessQuery('projects',{},read));
  await tick(); expect(result.current.data).toBe(testUser.userId);
  vi.mocked(authApi.me).mockResolvedValue(other);
  await tick(30_010); await tick();
  expect(result.current.data).toBe('other'); expect(read).toHaveBeenCalledTimes(2);
  expect(queryClient.getQueryData(businessKey(testUser,'projects',{}))).toBeUndefined(); unmount();
});

it('garbage collects an unused query after ten minutes', async () => {
  const read=vi.fn().mockResolvedValue('cached');
  const {unmount}=renderHook(()=>useBusinessQuery('projects',{},read));
  await tick(); unmount();
  await tick(599_000); expect(queryClient.getQueryData(businessKey(testUser,'projects',{}))).toBe('cached');
  await tick(1_010); expect(queryClient.getQueryData(businessKey(testUser,'projects',{}))).toBeUndefined();
});

it('shares the same initial read and polling request between matching observers', async () => {
  const read=vi.fn().mockResolvedValue({total:1});
  const first=renderHook(()=>useBusinessQuery('projects',{page:1},read));
  const second=renderHook(()=>useBusinessQuery('projects',{page:1},read));
  await tick(); expect(read).toHaveBeenCalledOnce();
  await tick(30_010); expect(read).toHaveBeenCalledTimes(2);
  expect(authApi.me).toHaveBeenCalledOnce(); first.unmount(); second.unmount();
});

it('does not start overlapping polls when a previous request remains slow', async () => {
  let finish!: (value:string)=>void;
  const read=vi.fn().mockResolvedValueOnce('cached').mockImplementation(()=>new Promise<string>(resolve=>{finish=resolve}));
  const {result,unmount}=renderHook(()=>useBusinessQuery('projects',{},read));
  await tick(); expect(result.current.data).toBe('cached');
  await tick(90_010); expect(read).toHaveBeenCalledTimes(2); expect(result.current.data).toBe('cached');
  act(()=>{finish('new')}); await tick(); expect(result.current.data).toBe('new'); unmount();
});

it('does not re-render a normal list when background checks return unchanged data', async () => {
  // JSON responses create new objects even when every profile field is equal.
  vi.mocked(authApi.me).mockImplementation(()=>Promise.resolve(structuredClone(testUser)));
  const read=vi.fn(()=>Promise.resolve({items:[{id:'one',revision:1}],total:1}));
  let renders=0;
  const wrapper=({children}:PropsWithChildren)=><MemoryRouter future={{v7_startTransition:true,v7_relativeSplatPath:true}}>{children}</MemoryRouter>;
  const {result,unmount}=renderHook(()=>{
    const url=useListUrl(); const query=useBusinessQuery('projects',{page:1},read);
    useListPageBounds(url,query); renders++;
    return query.data;
  },{wrapper});
  await tick(); expect(result.current?.total).toBe(1); const before=renders;
  await tick(60_010); expect(read).toHaveBeenCalledTimes(3); expect(renders).toBe(before); unmount();
});

it('reads the server again after a fresh page runtime discards the in-memory cache', async () => {
  const read=vi.fn().mockResolvedValueOnce('old').mockResolvedValue('server-updated');
  const first=renderHook(()=>useBusinessQuery('projects',{},read));
  await tick(); expect(first.result.current.data).toBe('old'); first.unmount();
  queryClient.clear();
  const second=renderHook(()=>useBusinessQuery('projects',{},read));
  expect(second.result.current.data).toBeUndefined();
  await tick(); expect(second.result.current.data).toBe('server-updated'); expect(read).toHaveBeenCalledTimes(2); second.unmount();
});
