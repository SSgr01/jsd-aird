import { afterEach, describe, expect, it, vi } from 'vitest';
import { EditorChanges, isPersistentMutation } from './editor-changes';
import { editorUnchanged, mergeAcknowledgement, pauseWorkspaceInputs, snapshotBlob } from './editor-save';
import type { EditorHandle } from './types';

afterEach(() => vi.useRealTimers());
describe('editor changes without full snapshots', () => {
  it('keeps input paused until all overlapping status transitions finish', () => {
    const root = document.createElement('section'), first = pauseWorkspaceInputs(root), second = pauseWorkspaceInputs(root);
    first(); first(); expect(root.inert).toBe(true); second(); expect(root.inert).toBe(false);
  });
  it('drains mutations started by field synchronization before an explicit save flush returns', () => {
    const notify = vi.fn(), record = vi.fn(), synchronize = vi.fn();
    const tracker = new EditorChanges(notify, record);
    tracker.change(); tracker.synchronize('first', () => { tracker.change(); tracker.synchronize('follow-up', synchronize); });
    tracker.flush(); expect(record).toHaveBeenCalledTimes(2); expect(synchronize).toHaveBeenCalledTimes(1); expect(notify).toHaveBeenCalledTimes(1);
    tracker.dispose();
  });
  it('records every mutation synchronously and batches notifications and ordered effects', () => {
    vi.useFakeTimers(); const notify = vi.fn(), record = vi.fn(), events: string[] = [];
    const tracker = new EditorChanges(notify, record);
    tracker.change(); tracker.enqueue(() => events.push('row1'));
    tracker.change(); tracker.enqueue(() => events.push('row2'));
    tracker.synchronize('A1', () => events.push('stale')); tracker.synchronize('A1', () => events.push('latest'));
    expect(record).toHaveBeenCalledTimes(2); expect(notify).not.toHaveBeenCalled();
    tracker.flush(); expect(events).toEqual(['row1', 'row2', 'latest']); expect(notify).toHaveBeenCalledTimes(1);
    vi.runAllTimers(); expect(notify).toHaveBeenCalledTimes(1); tracker.dispose();
  });
  it('flushes within 100ms when animation frames do not run, and flushes on blur', () => {
    vi.useFakeTimers(); const raf = vi.spyOn(window, 'requestAnimationFrame').mockReturnValue(9), notify = vi.fn();
    const tracker = new EditorChanges(notify, () => undefined); tracker.change();
    vi.advanceTimersByTime(100); expect(notify).toHaveBeenCalledTimes(1);
    tracker.change(); window.dispatchEvent(new Event('blur')); expect(notify).toHaveBeenCalledTimes(2);
    tracker.dispose(); raf.mockRestore();
  });
  it('flushes editor focus loss and page hiding synchronously', () => {
    const notify = vi.fn(), tracker = new EditorChanges(notify, () => {}), input = document.createElement('textarea');
    document.body.append(input); tracker.change(); input.dispatchEvent(new FocusEvent('blur'));
    expect(notify).toHaveBeenCalledTimes(1);
    tracker.change(); const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true); document.dispatchEvent(new Event('visibilitychange'));
    expect(notify).toHaveBeenCalledTimes(2); hidden.mockRestore(); tracker.dispose(); input.remove();
  });
  it('never delivers callbacks from a disposed instance', () => {
    vi.useFakeTimers(); const notify = vi.fn(), effect = vi.fn(); const tracker = new EditorChanges(notify, () => undefined);
    tracker.change(); tracker.enqueue(effect); tracker.dispose(); vi.runAllTimers();
    tracker.flush(); expect(notify).not.toHaveBeenCalled(); expect(effect).not.toHaveBeenCalled();
  });
  it('stops remaining effects if an earlier effect switches the document', () => {
    const notify = vi.fn(), effect = vi.fn(), tracker = new EditorChanges(notify, () => {});
    tracker.change(); tracker.enqueue(() => tracker.dispose()); tracker.enqueue(effect); tracker.flush();
    expect(effect).not.toHaveBeenCalled(); expect(notify).not.toHaveBeenCalled();
  });
  it.each(['doc.operation.set-selections', 'sheet.operation.set-selections', 'ui.operation.open-replace-dialog'])('ignores %s', id => {
    expect(isPersistentMutation({ id, type: 1 })).toBe(false);
  });
  it('recognizes text, image, style and undo mutations without reading their large parameters', () => {
    const params = { unitId: 'doc', get source(): never { throw new Error('image data must not be read'); } };
    for (const id of ['doc.mutation.set-drawing-apply', 'sheet.mutation.set-range-style', 'data-validation.mutation.addRule']) expect(isPersistentMutation({ id, type: 2, params })).toBe(true);
    expect(isPersistentMutation({ id: 'doc.mutation.rich-text-editing', type: 2, params: { actions: [{}] } })).toBe(true);
    expect(isPersistentMutation({ id: 'doc.mutation.rich-text-editing', type: 2, params: { actions: [] } })).toBe(false);
  });
  it('ignores hydration, permission and calculation updates', () => {
    expect(isPersistentMutation({ id: 'sheet.mutation.set-range-values', type: 2 }, { fromChangeset: true })).toBe(false);
    expect(isPersistentMutation({ id: 'sheet.mutation.set-permission', type: 2 })).toBe(false);
    expect(isPersistentMutation({ id: 'formula.mutation.set-formula-calculation-result', type: 2 })).toBe(false);
    expect(isPersistentMutation({ id: 'sheet.mutation.set-range-values', type: 2 }, { applyFormulaCalculationResult: true })).toBe(false);
  });
  it('prepares one immutable upload rather than retaining a live model as the upload body', async () => {
    const value = { body: { dataStream: 'before\r\n' } }; const spy = vi.spyOn(JSON, 'stringify');
    const blob = snapshotBlob(value, 'DOCX'); expect(spy).toHaveBeenCalledTimes(1);
    expect(snapshotBlob(blob, 'DOCX')).toBe(blob); expect(spy).toHaveBeenCalledTimes(1); spy.mockRestore();
    value.body.dataStream = 'after';
    const text = await new Promise<string>((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : ''); reader.onerror = reject; reader.readAsText(blob); });
    expect(JSON.parse(text)).toEqual({ body: { dataStream: 'before\r\n' } });
  });
  it('detects edits not yet delivered to the parent and replacement instances', () => {
    let revision = 1; const editor = { getContentRevision: () => revision } as EditorHandle;
    expect(editorUnchanged(editor, editor, 1)).toBe(true); revision++;
    expect(editorUnchanged(editor, editor, 1)).toBe(false);
    expect(editorUnchanged({ ...editor }, editor, revision)).toBe(false);
  });
  it('merges canonical server fields without overwriting edits made during the save', () => {
    type Metadata = { fields: Array<{ id: string; name: string; role?: string }> };
    const base: Metadata = { fields: [{ id: 'a', name: 'before', role: 'old' }, { id: 'delete', name: 'old' }] };
    const local: Metadata = { fields: [{ ...base.fields[0]!, name: 'new local' }, { id: 'local', name: 'added' }] };
    const saved: Metadata = { fields: [{ ...base.fields[0]!, role: 'canonical' }, { id: 'server', name: 'recognized' }] };
    expect(mergeAcknowledgement(base, local, saved)).toEqual({ fields: [{ id: 'a', name: 'new local', role: 'canonical' }, { id: 'local', name: 'added' }, { id: 'server', name: 'recognized' }] });
  });
});
