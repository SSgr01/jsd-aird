import type { SourceRef } from './protocol';

/** Stable UI key only. Authoritative evidence IDs come from the server, never this browser key. */
export function canonicalSourceJson(source: SourceRef): string {
  const value: Record<string, unknown> = { ...source };
  for (const key of [
    'publication_id',
    'url',
    'resource_id',
    'resource_type',
    'project_id',
    'retrieved_at',
  ]) {
    value[key] ??= null;
  }
  function sorted(item: unknown): unknown {
    if (Array.isArray(item)) return item.map(sorted);
    if (item !== null && typeof item === 'object') {
      return Object.fromEntries(
        Object.entries(item)
          .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
          .map(([key, entry]) => [key, sorted(entry)]),
      );
    }
    return item;
  }
  return JSON.stringify(sorted(value));
}

export function applyTextUpdate(current: string, text: string, replace = false): string {
  return replace ? text : current + text;
}
