import { act, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom';
import type { PropsWithChildren } from 'react';
import { useListKeyword, useListUrl } from './use-list-url';

it('batches filter/page setters without dropping filters and restores them on Back', async () => {
  const wrapper=({children}:PropsWithChildren)=><MemoryRouter initialEntries={['/projects/list?keyword=old&page=3','/projects/list?keyword=current&page=2']} initialIndex={1} future={{v7_startTransition:true,v7_relativeSplatPath:true}}>{children}</MemoryRouter>;
  const {result}=renderHook(()=>({url:useListUrl(),location:useLocation(),navigate:useNavigate()}),{wrapper});
  await act(async()=>{
    result.current.url.field<string>('keyword','')[1]('new');
    result.current.url.field<number>('page',1,false)[1](1);
    await Promise.resolve();
  });
  expect(result.current.url.text('keyword')).toBe('new');
  expect(result.current.url.number('page',1)).toBe(1);
  act(()=>{void result.current.navigate(-1)});
  await waitFor(()=>expect(result.current.url.text('keyword')).toBe('old'));
  expect(result.current.url.number('page',1)).toBe(3);
});

it('updates text immediately while the router commits the URL in a transition', async () => {
  function Filter() {
    const url=useListUrl();
    const [keyword,setKeyword]=useListKeyword(url);
    const location=useLocation();
    return <><input aria-label="筛选" value={keyword} onChange={event=>setKeyword(event.target.value)} /><output>{location.search}</output></>;
  }
  render(<MemoryRouter future={{v7_startTransition:true,v7_relativeSplatPath:true}}><Filter /></MemoryRouter>);
  const input=screen.getByRole('textbox',{name:'筛选'});
  fireEvent.change(input,{target:{value:'3333'}});
  expect(input).toHaveValue('3333');
  await waitFor(()=>expect(screen.getByRole('status')).toHaveTextContent('keyword=3333'));
});
