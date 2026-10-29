import { useCallback, useEffect, useRef, useState, type Dispatch, type SetStateAction } from 'react';
import { NavigationType, useLocation, useNavigationType, useSearchParams } from 'react-router-dom';

/** URL is the source of truth, so browser Back restores filters and pages too. */
export function useListUrl() {
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const pending = useRef<URLSearchParams>();
  const locationRef = useRef(location.key);
  locationRef.current = location.key;
  const update = useCallback((values: Record<string, string | number | undefined>, resetPage = false) => {
      const first = !pending.current;
      const next = pending.current ?? new URLSearchParams(params);
      if (resetPage) next.delete('page');
      Object.entries(values).forEach(([key, value]) => {
        if (value === undefined || value === '') next.delete(key);
        else next.set(key, String(value));
      });
      pending.current = next;
      const origin = locationRef.current;
      if (first) queueMicrotask(() => {
        const target = pending.current;
        pending.current = undefined;
        if (target && origin === locationRef.current) setParams(target, { replace: true });
      });
  }, [params, setParams]);
  const number = (key: string, fallback: number) => {
    const value = Number(params.get(key));
    return Number.isSafeInteger(value) && value > 0 ? value : fallback;
  };
  function field<T extends string | number | undefined>(key: string, fallback: T, resetPage = true): [T, Dispatch<SetStateAction<T>>] {
    const value = (typeof fallback === 'number' ? number(key, fallback) : params.get(key) || fallback) as T;
    return [value, (next) => update({ [key]: typeof next === 'function' ? next(value) : next }, resetPage)];
  }
  function pagination(total: number, defaultSize = 20) {
    const page = { current: number('page', 1), pageSize: number('size', defaultSize), total };
    const setPage: Dispatch<SetStateAction<typeof page>> = (next) => {
      const target = typeof next === 'function' ? next(page) : next;
      update({ page: target.current, size: target.pageSize });
    };
    return [page, setPage] as const;
  }
  return { params, update, field, pagination, text: (key: string) => params.get(key) || undefined, number };
}

/** Controlled text inputs need an urgent local value; URL transitions can lag
 * behind keystrokes. Ignore intermediate commits from our own pending write,
 * but always accept browser Back/Forward and externally restored locations. */
export function useListKeyword(url: ReturnType<typeof useListUrl>) {
  const location = useLocation();
  const navigation = useNavigationType();
  const value = url.text('keyword') || '';
  const [draft, setDraft] = useState(value);
  const requested = useRef<string>();
  useEffect(() => {
    if (navigation !== NavigationType.Replace || requested.current === undefined || requested.current === value) {
      requested.current = undefined;
      setDraft(value);
    }
  }, [location.key, navigation, value]);
  const update = (next: string) => {
    requested.current = next;
    setDraft(next);
    url.update({ keyword: next }, true);
  };
  return [draft, update] as const;
}
