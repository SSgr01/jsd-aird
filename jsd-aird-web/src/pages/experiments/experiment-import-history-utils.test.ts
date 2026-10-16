import { describe, expect, it, vi } from 'vitest';

import { loadAllPages } from './experiment-import-history-utils';

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
