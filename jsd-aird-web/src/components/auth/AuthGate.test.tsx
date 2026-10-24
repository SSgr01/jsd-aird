import { act, render, screen, waitFor } from '@testing-library/react';
import { useEffect } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { AuthGate } from './AuthGate';
import { useAuthStore } from '@/stores/auth-store';
import { authApi } from '@/services/auth/auth-api';
import { testUser } from '@/test/auth-user';

beforeEach(()=>{ vi.stubEnv('MODE','production'); useAuthStore.setState({user:testUser,status:'authenticated'}); });
afterEach(()=>{vi.unstubAllEnvs();vi.restoreAllMocks();});

it('keeps workspace state for ordinary profile refresh but releases it after a permission change', async()=>{
  let mounts=0; let releases=0;
  function Workspace(){ useEffect(()=>{mounts++;return()=>{releases++}},[]); return <p>工作区</p>; }
  render(<MemoryRouter future={{v7_startTransition:true,v7_relativeSplatPath:true}}><AuthGate><Workspace /></AuthGate></MemoryRouter>);
  act(()=>useAuthStore.setState({user:{...testUser,displayName:'更新的名称',permissions:[...testUser.permissions].reverse()}}));
  expect(mounts).toBe(1); expect(releases).toBe(0);
  act(()=>useAuthStore.setState({user:{...testUser,permissions:['project.view']}}));
  await waitFor(()=>expect(mounts).toBe(2)); expect(releases).toBe(1);
});

it('does not replace the workspace while background session validation is pending or offline', async()=>{
  vi.spyOn(authApi,'me').mockRejectedValue(new Error('offline'));
  render(<MemoryRouter future={{v7_startTransition:true,v7_relativeSplatPath:true}}><AuthGate><p>仍在编辑</p></AuthGate></MemoryRouter>);
  let request!:Promise<unknown>;
  act(()=>{request=useAuthStore.getState().load(true)});
  expect(screen.getByText('仍在编辑')).toBeInTheDocument();
  await act(async()=>{await request});
  expect(screen.getByText('仍在编辑')).toBeInTheDocument();
});

it('preserves an unsaved workspace when another tab refreshes the same identity', async()=>{
  let mounts=0;
  function Workspace(){useEffect(()=>{mounts++},[]);return <p>未保存内容</p>}
  vi.spyOn(authApi,'me').mockResolvedValue({...testUser});
  render(<MemoryRouter future={{v7_startTransition:true,v7_relativeSplatPath:true}}><AuthGate><Workspace /></AuthGate></MemoryRouter>);
  await act(async()=>{await useAuthStore.getState().resync()});
  expect(screen.getByText('未保存内容')).toBeInTheDocument(); expect(mounts).toBe(1);
});
