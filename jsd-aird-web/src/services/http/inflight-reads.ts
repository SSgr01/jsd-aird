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
