import { getSessionEpoch } from './auth-events';

let writeGeneration = 0;
const pending = new Map<string, Promise<unknown>>();

/** No settled cache: permission/session changes and every write fence new readers. */
export function invalidateInflightReads() {
  writeGeneration++;
  pending.clear();
}

export function sharedRead<T>(resource: string, read: () => Promise<T>): Promise<T> {
  const key = `${getSessionEpoch()}:${writeGeneration}:${resource}`;
  let request = pending.get(key) as Promise<T> | undefined;
  if (!request) {
    request = read();
    pending.set(key, request);
    const owned = request;
    void request.then(() => { if (pending.get(key) === owned) pending.delete(key); },
      () => { if (pending.get(key) === owned) pending.delete(key); });
  }
  // Workspaces annotate their snapshots locally. Concurrent consumers must not
  // share a mutable workbook, even though they share its network request.
  return request.then(value => structuredClone(value));
}

const abortablePending = new Map<string, { promise: Promise<unknown>; controller: AbortController; consumers: number }>();

/** Share a network transfer, aborting it only after its last consumer leaves. */
export function sharedAbortableRead<T>(resource: string, read: (signal: AbortSignal) => Promise<T>, signal?: AbortSignal): Promise<T> {
  if (signal?.aborted) return Promise.reject(new DOMException('Aborted', 'AbortError'));
  const key = `${getSessionEpoch()}:${writeGeneration}:${resource}`;
  let entry = abortablePending.get(key);
  if (!entry || entry.controller.signal.aborted) {
    const controller = new AbortController();
    entry = { promise: read(controller.signal), controller, consumers: 0 };
    abortablePending.set(key, entry);
    const owned = entry;
    void entry.promise.finally(() => { if (abortablePending.get(key) === owned) abortablePending.delete(key); }).catch(() => undefined);
  }
  const owned = entry;
  owned.consumers++;
  return new Promise<T>((resolve, reject) => {
    let settled = false;
    const finish = () => {
      if (settled) return false;
      settled = true; signal?.removeEventListener('abort', abort);
      if (--owned.consumers === 0) owned.controller.abort();
      return true;
    };
    const abort = () => { if (finish()) reject(new DOMException('Aborted', 'AbortError')); };
    signal?.addEventListener('abort', abort, { once: true });
    owned.promise.then(value => { if (finish()) resolve(value as T); }, error => { if (finish()) reject(error); });
  });
}
