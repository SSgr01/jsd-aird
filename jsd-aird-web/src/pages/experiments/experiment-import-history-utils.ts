import type { PageResponse } from '@/types/api';
import type { ExperimentSyncStatus } from '@/services/data/data-api';

export function assemblyActivity(status?: ExperimentSyncStatus): 'loading' | 'running' | 'ready' | 'idle' {
  if (!status) return 'loading';
  if (['READY', 'RUNNING'].includes(status.preparationStatus ?? '')
      || status.links.some(link => ['READY', 'RUNNING'].includes(link.status))) return 'running';
  return status.plan?.readyCount ? 'ready' : 'idle';
}

export async function loadAllPages<T>(
  loadPage: (page: number, size: number) => Promise<PageResponse<T>>,
  size = 100,
): Promise<T[]> {
  const first = await loadPage(1, size);
  if (first.totalPages <= 1) return first.items;
  const remaining = await Promise.all(Array.from(
    { length: first.totalPages - 1 },
    (_, index) => loadPage(index + 2, size),
  ));
  return [...first.items, ...remaining.flatMap((page) => page.items)];
}
