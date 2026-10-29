import { renderHook, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { PropsWithChildren } from 'react';
import { useListUrl } from './use-list-url';
import { useListPageBounds } from './use-list-page-bounds';

const wrapper = ({children}:PropsWithChildren) => <MemoryRouter initialEntries={['/list?page=3&size=10&keyword=keep']} future={{v7_startTransition:true,v7_relativeSplatPath:true}}>{children}</MemoryRouter>;
const ready = { data: { total: 20 }, isSuccess: true, isFetching: false, isPlaceholderData: false };

it('returns to the last valid page after deletion while preserving filters', async () => {
  const {result} = renderHook(() => { const url=useListUrl(); useListPageBounds(url,ready); return url; },{wrapper});
  await waitFor(() => expect(result.current.number('page',1)).toBe(2));
  expect(result.current.text('keyword')).toBe('keep');
});

it('returns an emptied list to page one', async () => {
  const {result} = renderHook(() => { const url=useListUrl(); useListPageBounds(url,{...ready,data:{total:0}}); return url; },{wrapper});
  await waitFor(() => expect(result.current.number('page',1)).toBe(1));
});

it.each([{isFetching:true},{isPlaceholderData:true},{isSuccess:false}])('does not clamp using incomplete or stale totals: %j', flags => {
  const {result} = renderHook(() => { const url=useListUrl(); useListPageBounds(url,{...ready,...flags}); return url; },{wrapper});
  expect(result.current.number('page',1)).toBe(3);
});
