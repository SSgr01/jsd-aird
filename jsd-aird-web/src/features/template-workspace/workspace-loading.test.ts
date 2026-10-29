import { act, renderHook } from '@testing-library/react';
import { useWorkspaceLoadGeneration } from './workspace-loading';

it('rejects results from replaced loads and unmounted workspaces',()=>{
  const {result,unmount}=renderHook(()=>useWorkspaceLoadGeneration());
  const first=result.current();
  const second=result.current();
  expect(first()).toBe(false);
  expect(second()).toBe(true);
  act(()=>unmount());
  expect(second()).toBe(false);
});
