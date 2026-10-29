import { act, renderHook, waitFor } from '@testing-library/react';
import { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { businessKey, useBusinessQuery } from './use-business-query';
import { createQueryClient, queryClient } from './query-client';
import { httpClient } from '@/services/http/client';
import { useAuthStore } from '@/stores/auth-store';
import { testUser } from '@/test/auth-user';

interface Row { id: string; title: string; category: string; revision: number }
interface Page { items: Row[]; total: number }
const originalAdapter = httpClient.defaults.adapter;
let rows: Row[];
let reads: number;
let writes: number;
let holdRead: boolean;
let held: Array<{ release: () => void; signal?: AbortSignal }>;
let writeFailure: number | 'timeout' | undefined;
let commitBeforeFailure: boolean;
let readFailures: number[];
let deleteRows: boolean;

function response(config: InternalAxiosRequestConfig, data: unknown, status = 200) {
  return { config, data: {data}, status, statusText:'', headers:{} };
}
function failure(config: InternalAxiosRequestConfig, status: number) {
  return new AxiosError('fixture error','ERR_BAD_RESPONSE',config,undefined,response(config,{},status));
}
function read(signal: AbortSignal) {
  return httpClient.get<{data:Page}>('/api/v1/projects',{signal}).then(result=>result.data.data);
}
function save() {
  return httpClient.post('/api/v1/projects/save',{}, {headers:{'X-XSRF-TOKEN':'fixture-token'}});
}

beforeEach(()=>{
  queryClient.clear();
  const defaults=createQueryClient().getDefaultOptions();
  queryClient.setDefaultOptions({...defaults,queries:{...defaults.queries,retryDelay:0}});
  useAuthStore.setState({user:testUser,status:'authenticated'});
  rows=[{id:'one',title:'before',category:'A',revision:1}];
  reads=0;writes=0;holdRead=false;held=[];writeFailure=undefined;commitBeforeFailure=false;readFailures=[];deleteRows=false;
  httpClient.defaults.adapter=config=>{
    if (config.method==='get') {
      reads++;
      const status=readFailures.shift();
      if(status)return Promise.reject(failure(config,status));
      const captured=structuredClone({items:rows,total:rows.length});
      if(holdRead){holdRead=false;return new Promise(resolve=>held.push({release:()=>resolve(response(config,captured)),signal:config.signal as AbortSignal}));}
      return Promise.resolve(response(config,captured));
    }
    writes++;
    if(!writeFailure||commitBeforeFailure)rows=deleteRows?[]:rows.map(row=>({...row,title:'after',category:'B',revision:row.revision+1}));
    if(writeFailure==='timeout')return Promise.reject(new AxiosError('timeout','ECONNABORTED',config));
    if(writeFailure)return Promise.reject(failure(config,writeFailure));
    return Promise.resolve(response(config,rows[0]));
  };
});
afterEach(()=>{
  queryClient.clear();
  held.forEach(request=>request.release());
  httpClient.defaults.adapter=originalAdapter;
});

it('refetches after a save even if the very first read is still in flight',async()=>{
  holdRead=true;
  const {result}=renderHook(()=>useBusinessQuery('projects',{page:1},read));
  await waitFor(()=>expect(held).toHaveLength(1));
  await act(async()=>{await save()});
  await act(async()=>{held[0]!.release();await Promise.resolve()});
  await waitFor(()=>expect(result.current.data?.items[0]?.revision).toBe(2));
  expect(reads).toBe(2);
  expect(held[0]?.signal?.aborted).toBe(true);
});

it('does not let an older background response overwrite the saved revision',async()=>{
  const {result}=renderHook(()=>useBusinessQuery('projects',{page:1},read));
  await waitFor(()=>expect(result.current.data?.items[0]?.revision).toBe(1));
  holdRead=true;
  act(()=>{void result.current.refetch()});
  await waitFor(()=>expect(held).toHaveLength(1));
  await act(async()=>{await save()});
  await waitFor(()=>expect(result.current.data?.items[0]?.revision).toBe(2));
  await act(async()=>{held[0]!.release();await Promise.resolve()});
  expect(result.current.data?.items[0]?.revision).toBe(2);
});

it('discards known-invalid inactive filters, categories and totals after a mutation',async()=>{
  const keys=[{page:2,category:'A'},{page:1,category:'B'},{kind:'categories'},{kind:'counts'}].map(params=>businessKey(testUser,'projects',params));
  keys.forEach(key=>queryClient.setQueryData(key,{items:rows,total:1}));
  await act(async()=>{await save()});
  await waitFor(()=>keys.forEach(key=>expect(queryClient.getQueryData(key)).toBeUndefined()));
});

it.each([400,422])('keeps valid cached rows and does not retry a rejected %s write',async status=>{
  const {result}=renderHook(()=>useBusinessQuery('projects',{},read));
  await waitFor(()=>expect(result.current.data?.items[0]?.revision).toBe(1));
  writeFailure=status;
  await act(async()=>{await expect(save()).rejects.toMatchObject({status})});
  expect(result.current.data?.items[0]?.revision).toBe(1);
  expect(writes).toBe(1);expect(reads).toBe(1);
});

it('refreshes read caches after a revision conflict without retrying the write',async()=>{
  const {result}=renderHook(()=>useBusinessQuery('projects',{},read));
  await waitFor(()=>expect(result.current.data?.items[0]?.revision).toBe(1));
  rows=rows.map(row=>({...row,revision:2}));writeFailure=409;
  await act(async()=>{await expect(save()).rejects.toMatchObject({status:409})});
  await waitFor(()=>expect(result.current.data?.items[0]?.revision).toBe(2));
  expect(writes).toBe(1);
});

it.each(['timeout',500] as const)('reconciles an ambiguous %s write by reading, never by sending it twice',async status=>{
  const {result}=renderHook(()=>useBusinessQuery('projects',{},read));
  await waitFor(()=>expect(result.current.data?.items[0]?.revision).toBe(1));
  writeFailure=status;commitBeforeFailure=true;
  await act(async()=>{await expect(save()).rejects.toThrow()});
  await waitFor(()=>expect(result.current.data?.items[0]?.revision).toBe(2));
  expect(writes).toBe(1);
});

it('retries a 503 read once and recovers the newest data',async()=>{
  readFailures=[503];
  const {result}=renderHook(()=>useBusinessQuery('projects',{},read));
  await waitFor(()=>expect(result.current.isSuccess).toBe(true));
  expect(reads).toBe(2);
});

it.each([404,429])('does not retry a %s read or log the user out',async status=>{
  readFailures=[status];
  const {result}=renderHook(()=>useBusinessQuery('projects',{},read));
  await waitFor(()=>expect(result.current.isError).toBe(true));
  expect(reads).toBe(1);expect(useAuthStore.getState().status).toBe('authenticated');
});

it('aborts a pending read when its page unmounts',async()=>{
  holdRead=true;
  const {unmount}=renderHook(()=>useBusinessQuery('projects',{},read));
  await waitFor(()=>expect(held).toHaveLength(1));
  unmount();
  expect(held[0]?.signal?.aborted).toBe(true);
});

it('converges on the last committed revision across overlapping writes and a stale refresh',async()=>{
  const {result}=renderHook(()=>useBusinessQuery('projects',{},read));
  await waitFor(()=>expect(result.current.data?.items[0]?.revision).toBe(1));
  holdRead=true; act(()=>{void result.current.refetch()});
  await waitFor(()=>expect(held).toHaveLength(1));
  await act(async()=>{await Promise.all([save(),save(),save()])});
  await waitFor(()=>expect(result.current.data?.items[0]?.revision).toBe(4));
  await act(async()=>{held[0]!.release();await Promise.resolve()});
  expect(result.current.data?.items[0]?.revision).toBe(4); expect(writes).toBe(3);
});

it('removes deleted rows from visible lists and count observers',async()=>{
  const list=renderHook(()=>useBusinessQuery('projects',{page:1},read));
  const counts=renderHook(()=>useBusinessQuery('projects',{kind:'counts'},read));
  await waitFor(()=>expect(list.result.current.data?.total).toBe(1));
  await waitFor(()=>expect(counts.result.current.data?.total).toBe(1));
  deleteRows=true;
  await act(async()=>{await save()});
  await waitFor(()=>expect(list.result.current.data).toEqual({items:[],total:0}));
  await waitFor(()=>expect(counts.result.current.data?.total).toBe(0));
});

it('recovers read access after write-only 403 without clearing unrelated data',async()=>{
  const unrelated=businessKey(testUser,'knowledge',{page:1}); queryClient.setQueryData(unrelated,['readable']);
  const {result}=renderHook(()=>useBusinessQuery('projects',{},read));
  await waitFor(()=>expect(result.current.data?.total).toBe(1));
  writeFailure=403;
  await act(async()=>{await expect(save()).rejects.toMatchObject({status:403})});
  await waitFor(()=>expect(reads).toBe(2));
  await waitFor(()=>expect(result.current.isSuccess).toBe(true));
  expect(result.current.data?.total).toBe(1); expect(writes).toBe(1);
  expect(queryClient.getQueryData(unrelated)).toEqual(['readable']);
});

it('clears protected rows on a background 403 and rejects a sibling late response',async()=>{
  const list=renderHook(()=>useBusinessQuery('projects',{page:1},read));
  const sibling=renderHook(()=>useBusinessQuery('projects',{page:2},read));
  await waitFor(()=>expect(list.result.current.data?.total).toBe(1));
  await waitFor(()=>expect(sibling.result.current.data?.total).toBe(1));
  holdRead=true; act(()=>{void sibling.result.current.refetch()});
  await waitFor(()=>expect(held).toHaveLength(1));
  readFailures=[403];
  await act(async()=>{await list.result.current.refetch()});
  await waitFor(()=>expect(list.result.current.data).toBeUndefined());
  await act(async()=>{held[0]!.release();await Promise.resolve()});
  await waitFor(()=>expect(sibling.result.current.data).toBeUndefined());
  expect(held[0]?.signal?.aborted).toBe(true);
  expect(useAuthStore.getState().status).toBe('authenticated');
});

it('does not refill private data when a background 401 ends the session',async()=>{
  const {result}=renderHook(()=>useBusinessQuery('projects',{},read));
  await waitFor(()=>expect(result.current.data?.total).toBe(1));
  readFailures=[401]; await act(async()=>{await result.current.refetch()});
  expect(useAuthStore.getState().status).toBe('anonymous');
  await waitFor(()=>expect(result.current.data).toBeUndefined());
  expect(reads).toBe(2);
});
