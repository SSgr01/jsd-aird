import { advanceSessionEpoch } from './auth-events';
import { invalidateInflightReads, sharedRead } from './inflight-reads';

function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { promise, resolve }; }
beforeEach(() => invalidateInflightReads());

it('shares pending reads, isolates mutable snapshots and retains no settled data', async () => {
  const pending = deferred<{ snapshot: { v: number } }>();
  const read = vi.fn(() => pending.promise);
  const first = sharedRead('record:revision-1', read), second = sharedRead('record:revision-1', read);
  expect(read).toHaveBeenCalledTimes(1);
  pending.resolve({ snapshot: { v: 1 } });
  const [a, b] = await Promise.all([first, second]);
  a.snapshot.v = 2;
  expect(b.snapshot.v).toBe(1);
  await sharedRead('record:revision-1', read);
  expect(read).toHaveBeenCalledTimes(2);
});

it('does not merge reads across writes, revisions or permission/session changes', async () => {
  const old = deferred<{ v: number }>(), next = deferred<{ v: number }>();
  const read = vi.fn().mockReturnValueOnce(old.promise).mockReturnValue(next.promise);
  const before = sharedRead('record:1', read);
  invalidateInflightReads();
  const after = sharedRead('record:1', read);
  advanceSessionEpoch();
  const permissionChange = sharedRead('record:1', read);
  const otherRevision = sharedRead('record:2', read);
  expect(read).toHaveBeenCalledTimes(4);
  old.resolve({ v: 0 }); next.resolve({ v: 1 });
  expect(await after).toEqual({ v: 1 });
  await Promise.all([before, permissionChange, otherRevision]);
});

it('does not retain a denied or failed read', async () => {
  const read = vi.fn().mockRejectedValueOnce(new Error('403')).mockResolvedValue({ v: 1 });
  await expect(sharedRead('record', read)).rejects.toThrow('403');
  expect(await sharedRead('record', read)).toEqual({ v: 1 });
  expect(read).toHaveBeenCalledTimes(2);
});
