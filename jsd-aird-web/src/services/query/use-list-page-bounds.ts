import { useEffect } from 'react';
import type { useListUrl } from './use-list-url';

/** A deletion can remove the last page. Only trust a completed real response;
 * previous-page placeholders and failed refreshes cannot determine the bound. */
export function useListPageBounds(
  url: ReturnType<typeof useListUrl>,
  query: {
    data?: { total: number };
    isSuccess: boolean;
    isFetching: boolean;
    isPlaceholderData: boolean;
  },
  defaultSize = 20,
) {
  const page = url.number('page', 1);
  const size = url.number('size', defaultSize);
  const total = query.data?.total;
  const lastPage = total !== undefined && Number.isFinite(total) && total >= 0
    ? Math.max(1, Math.ceil(total / size)) : undefined;
  // Normal pages only observe data. Subscribing to isFetching unconditionally
  // would re-render a large list twice on every unchanged background check.
  const ready = lastPage !== undefined && page > lastPage && query.isSuccess && !query.isFetching && !query.isPlaceholderData;
  const update = url.update;
  useEffect(() => {
    if (ready && lastPage !== undefined) update({ page: lastPage });
  }, [lastPage, ready, update]);
}
