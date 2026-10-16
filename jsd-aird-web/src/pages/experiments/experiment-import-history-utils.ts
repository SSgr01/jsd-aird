import type { PageResponse } from '@/types/api';

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
