import { createQuerySync } from './query-sync';

function channels() {
  const peers = Array.from({ length: 2 }, () => ({
    onmessage: null as ((event: MessageEvent<unknown>) => void) | null,
    postMessage: vi.fn<(message: unknown) => void>(),
    close: vi.fn(),
  }));
  // Each channel gets its own sender identity, as two independent tabs do.
  for (const peer of peers) peer.postMessage = vi.fn((message: unknown) => peers.forEach(other => { if (other !== peer) other.onmessage?.(new MessageEvent('message', { data: message })); }));
  return peers as [typeof peers[number], typeof peers[number]];
}
const scope = { organizationId: 'org', userId: 'alice', authVersion: 1 };
const options = () => ({ scope: () => scope, invalidate: vi.fn(), session: vi.fn() });

it('refreshes another tab after a write without copying rows or echoing messages', () => {
  const [a,b] = channels(); const first = options(); const second = options();
  const one = createQuerySync(first,a); const two = createQuerySync(second,b);
  one.invalidate(['projects','projects','data','unknown']);
  expect(second.invalidate).toHaveBeenCalledExactlyOnceWith(['projects','data']);
  expect(first.invalidate).not.toHaveBeenCalled();
  expect(b.postMessage).not.toHaveBeenCalled();
  expect(Object.keys(a.postMessage.mock.calls[0]![0] as object).sort()).toEqual(['domains','scope','sender','type','version']);
  one.dispose(); two.dispose();
});

it.each([
  { ...scope, organizationId: 'other' }, { ...scope, userId: 'bob' }, { ...scope, authVersion: 2 }, null,
])('ignores invalidation from a different identity or permission scope: %j', current => {
  const [a,b] = channels(); const second = { ...options(), scope: () => current };
  const one = createQuerySync(options(),a); const two = createQuerySync(second,b);
  one.invalidate(['projects']); expect(second.invalidate).not.toHaveBeenCalled();
  one.dispose(); two.dispose();
});

it('propagates shared-cookie logout/login without broadcasting credentials', () => {
  const [a,b] = channels(); const second = options();
  const one = createQuerySync(options(),a); const two = createQuerySync(second,b);
  one.session('ended'); one.session('changed');
  expect(second.session.mock.calls).toEqual([['ended'],['changed']]);
  expect(JSON.stringify(a.postMessage.mock.calls)).not.toMatch(/password|token|rows/);
  one.dispose(); two.dispose();
});

it('ignores malformed payloads and its own sender, and tolerates a closed channel', () => {
  const [a] = channels(); const callbacks = options(); const bridge = createQuerySync(callbacks,a);
  for (const data of [null,{},'string',{version:2,sender:'x',type:'session',action:'ended'},{version:1,sender:'x',type:'invalidate',scope,domains:'projects'},{version:1,sender:'x',type:'invalidate',scope,domains:[42,'invalid']},{version:1,sender:'x',type:'session',action:'invalid'}]) a.onmessage?.(new MessageEvent('message',{data}));
  bridge.invalidate(['projects']); a.onmessage?.(new MessageEvent('message',{data:a.postMessage.mock.calls[0]![0]}));
  expect(callbacks.invalidate).not.toHaveBeenCalled(); expect(callbacks.session).not.toHaveBeenCalled();
  a.postMessage.mockImplementation(() => { throw new Error('closed'); });
  expect(() => bridge.invalidate(['projects'])).not.toThrow();
  bridge.dispose(); expect(a.onmessage).toBeNull(); expect(a.close).toHaveBeenCalledOnce();
});
