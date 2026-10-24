import { act, fireEvent, render, screen } from '@testing-library/react';
import { useEffect, type ReactNode } from 'react';
import { MemoryRouter, Link, Outlet, Route, Routes } from 'react-router-dom';
import { ContentBoundary } from './ContentBoundary';
import { ContentLoading } from './ContentLoading';
import { routePage } from '@/routes/route-preload';

it('keeps navigation and previous content while the next chunk loads',async()=>{
  let mounts=0;
  let finish!: (module:{default:()=>ReactNode})=>void;
  const Slow=routePage(()=>new Promise<{default:()=>ReactNode}>(resolve=>{finish=resolve}));
  function Shell(){useEffect(()=>{mounts++},[]);return <><header><span>固定导航</span><Link to="/slow">切换</Link></header><ContentBoundary><Outlet /></ContentBoundary></>}
  render(<MemoryRouter future={{v7_startTransition:true,v7_relativeSplatPath:true}}><Routes><Route element={<ContentBoundary startup><Outlet /></ContentBoundary>}><Route element={<Shell />}><Route path="/" element={<p>原来的内容</p>} /><Route path="/slow" element={<Slow />} /></Route></Route></Routes></MemoryRouter>);
  fireEvent.click(screen.getByText('切换'));
  expect(screen.getByText('固定导航')).toBeInTheDocument();
  expect(screen.getByText('原来的内容')).toBeInTheDocument();
  await act(async()=>{finish({default:()=> <p>新的内容</p>});await Promise.resolve()});
  expect(await screen.findByText('新的内容')).toBeInTheDocument();
  expect(mounts).toBe(1);
});

it('retries failed chunks locally while keeping navigation',async()=>{
  const error=vi.spyOn(console,'error').mockImplementation(()=>{});
  const importer=vi.fn<()=>Promise<{default:()=>ReactNode}>>().mockRejectedValueOnce(new Error('network')).mockResolvedValue({default:()=> <p>重试成功</p>});
  const Page=routePage(importer);
  render(<MemoryRouter future={{v7_startTransition:true,v7_relativeSplatPath:true}}><nav>导航仍可用</nav><ContentBoundary><Page /></ContentBoundary></MemoryRouter>);
  fireEvent.click(await screen.findByText('重试当前页面'));
  expect(await screen.findByText('重试成功')).toBeInTheDocument();
  expect(screen.getByText('导航仍可用')).toBeInTheDocument();
  expect(importer).toHaveBeenCalledTimes(2);
  error.mockRestore();
});

it('only reveals content loading after 200 milliseconds',()=>{
  vi.useFakeTimers();
  render(<ContentLoading label="加载测试内容" />);
  expect(screen.queryByText('加载测试内容')).not.toBeInTheDocument();
  act(()=>{ vi.advanceTimersByTime(199); });
  expect(screen.queryByText('加载测试内容')).not.toBeInTheDocument();
  act(()=>{ vi.advanceTimersByTime(1); });
  expect(screen.getByText('加载测试内容')).toBeInTheDocument();
  vi.useRealTimers();
});
