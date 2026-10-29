import { useEffect, useRef } from 'react';
import { flushSync } from 'react-dom';
import type { EditorHandle, TemplateFormat } from './types';

/** Commit queued field callbacks before callers read their latest React state refs. */
export function flushEditorChanges(editor: EditorHandle | null) {
  if (editor?.flushChanges) flushSync(() => editor.flushChanges?.());
}
export function snapshotBlob(snapshot: Record<string, unknown> | Blob, format: TemplateFormat): Blob {
  if (snapshot instanceof Blob) return snapshot;
  const valid = snapshot && typeof snapshot === 'object' && !Array.isArray(snapshot)
    && (format === 'DOCX' ? typeof (snapshot.body as { dataStream?: unknown } | undefined)?.dataStream === 'string'
      : snapshot.sheets && typeof snapshot.sheets === 'object' && !Array.isArray(snapshot.sheets) && Object.keys(snapshot.sheets).length > 0);
  if (!valid) throw new Error('编辑器快照无效，已取消保存');
  return new Blob([JSON.stringify(snapshot)], { type: 'application/json' });
}
/** Identity changes even when navigation goes A -> B -> A before a response arrives. */
export function useSaveSession(key: string | undefined) {
  const ref = useRef({ key, active: true });
  if (ref.current.key !== key) ref.current = { key, active: true };
  useEffect(() => { const session = ref.current; session.active = true; return () => { session.active = false; }; }, [key]);
  return ref;
}
export function editorUnchanged(editor: EditorHandle | null, captured: EditorHandle | null, revision: number | undefined) {
  return editor === captured && editor?.getContentRevision?.() === revision;
}

/** Briefly stop user input during an irreversible status transition, never during autosave. */
const inputPauses = new WeakMap<HTMLElement, { count: number; previous: boolean }>();
export function pauseWorkspaceInputs(root: HTMLElement | null) {
  if (!root) return () => {};
  const pause = inputPauses.get(root) ?? { count: 0, previous: root.inert ?? false };
  pause.count++; inputPauses.set(root, pause); root.inert = true;
  let released = false;
  return () => {
    if (released) return;
    released = true;
    if (--pause.count === 0) { root.inert = pause.previous; inputPauses.delete(root); }
  };
}

function record(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value); }
function identity(value: unknown): string | undefined {
  if (!record(value)) return undefined;
  for (const key of ['bindingId', 'id', 'blockId', 'operationId']) if (typeof value[key] === 'string') return `${key}:${value[key]}`;
  return undefined;
}
/** Three-way acknowledgement of small field/schema metadata, never editor/image snapshots. */
export function mergeAcknowledgement<T>(base: T, local: T, saved: T): T {
  if (Object.is(base, local)) return saved;
  if (Array.isArray(base) && Array.isArray(local) && Array.isArray(saved)) {
    if (![...base, ...local, ...saved].every(item => identity(item) !== undefined)) return local;
    const before = new Map(base.map(item => [identity(item), item as unknown]));
    const after = new Map(saved.map(item => [identity(item), item as unknown]));
    const current = new Map(local.map(item => [identity(item), item as unknown]));
    const result: unknown[] = [];
    for (const [key, value] of current) {
      if (before.has(key) && !after.has(key) && Object.is(before.get(key), value)) continue;
      result.push(after.has(key) && before.has(key) ? mergeAcknowledgement(before.get(key), value, after.get(key)) : value);
    }
    for (const [key, value] of after) if (!before.has(key) && !current.has(key)) result.push(value);
    return result as T;
  }
  if (record(base) && record(local) && record(saved)) {
    const result: Record<string, unknown> = {};
    for (const key of new Set([...Object.keys(local), ...Object.keys(saved)])) {
      if (!(key in local) && key in base) continue; // A local deletion is intentional.
      if (!(key in saved) && key in base && Object.is(local[key], base[key])) continue;
      result[key] = !(key in base) ? (key in local ? local[key] : saved[key])
        : mergeAcknowledgement(base[key], local[key], saved[key]);
    }
    return result as T;
  }
  return local;
}
