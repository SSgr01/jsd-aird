import { describe, expect, it, vi } from 'vitest';

import { assemblyActivity, loadAllPages } from './experiment-import-history-utils';
import type { ExperimentSyncStatus } from '@/services/data/data-api';

describe('assemblyActivity', () => {
  it('does not mistake a ready plan for a running background task', () => {
    const status: ExperimentSyncStatus = { preparationStatus: 'SUCCEEDED', links: [], plan: {
      importJobId: 'import-1', candidates: [], readyCount: 4, reviewCount: 0, blockedCount: 0,
      sourceRecordCount: 4, sampleGroupCount: 4, sourceGroupCount: 0, sourceContextCount: 0,
      sourceIdentityCount: 1, logicalSampleCount: 4,
    } };
    expect(assemblyActivity(status)).toBe('ready');
    expect(assemblyActivity({ ...status, preparationStatus: 'RUNNING' })).toBe('running');
  });

  it('tracks pending assembly links after preparation has finished', () => {
    const status = { preparationStatus: 'SUCCEEDED', plan: null, links: [{ status: 'READY' }] } as ExperimentSyncStatus;
    expect(assemblyActivity(status)).toBe('running');
    expect(assemblyActivity({ ...status, links: [] })).toBe('idle');
    expect(assemblyActivity()).toBe('loading');
  });
});

describe('loadAllPages', () => {
  it('loads every API page instead of truncating history at the first 100 tasks', async () => {
    const allItems = Array.from({ length: 237 }, (_, index) => ({ id: index + 1 }));
    const loadPage = vi.fn(async (page: number, size: number) => {
      const items = allItems.slice((page - 1) * size, page * size);
      return {
        items,
        page,
        size,
        total: allItems.length,
        totalPages: Math.ceil(allItems.length / size),
      };
    });

    const result = await loadAllPages(loadPage);

    expect(result).toHaveLength(237);
    expect(result[0]?.id).toBe(1);
    expect(result.at(-1)?.id).toBe(237);
    expect(loadPage.mock.calls.map(([page]) => page)).toEqual([1, 2, 3]);
  });
});
