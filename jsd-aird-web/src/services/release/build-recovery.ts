import { useSyncExternalStore } from 'react';

interface BuildInfo { component: 'frontend'; revision: string; builtAt: string }
let available: BuildInfo | null = null;
let pending: Promise<BuildInfo | null> | undefined;
const listeners = new Set<() => void>();
const key = 'aird:asset-recovery-revision';
const compiledRevision = import.meta.env.VITE_BUILD_REVISION;

export function isChunkError(error: unknown) {
  return error instanceof Error && /Failed to fetch dynamically imported module|Importing a module script failed|Loading (?:CSS )?chunk|Unable to preload CSS|error loading dynamically imported module/i.test(error.message);
}

export function checkBuildVersion(): Promise<BuildInfo | null> {
  if (pending) return pending;
  pending = (async () => {
    try {
      const response = await fetch('/build-info.json', { cache: 'no-store', signal: AbortSignal.timeout(5000) });
      if (!response.ok) return null;
      const info = await response.json() as Partial<BuildInfo>;
      if (info.component !== 'frontend' || !/^[a-f0-9]{40}$/.test(info.revision ?? '') || typeof info.builtAt !== 'string' || info.revision === compiledRevision) return null;
      available = info as BuildInfo;
      listeners.forEach((listener) => listener());
      return available;
    } catch { return null; }
    finally { pending = undefined; }
  })();
  return pending;
}

export function installBuildRecovery() {
  window.addEventListener('vite:preloadError', () => { void checkBuildVersion(); });
  // Do not preventDefault: failed imports must still reach their local boundary.
}

export function useAvailableBuild() {
  return useSyncExternalStore((listener) => { listeners.add(listener); return () => listeners.delete(listener); }, () => available);
}

export function canRecoverRevision(revision: string) {
  try { return sessionStorage.getItem(key) !== revision; } catch { return false; }
}

/** Called only by a visible user action. Native beforeunload guards still run. */
export function recoverBuild(revision: string, reload = () => window.location.reload()) {
  if (!canRecoverRevision(revision)) return false;
  try { sessionStorage.setItem(key, revision); } catch { return false; }
  reload();
  return true;
}
