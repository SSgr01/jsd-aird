import { act, render, waitFor } from '@testing-library/react';
import { createRef } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { EditorHandle, TemplateBinding } from './types';
import { UniverDocsEditor } from './UniverDocsEditor';

const sdk = vi.hoisted(() => {
  const raw = { id: 'doc', body: { dataStream: 'before\r\n', customRanges: [{ rangeId: 'marker', startIndex: 0, endIndex: 5 }] } };
  return { raw, save: vi.fn(() => raw), rawRead: vi.fn(() => raw), create: vi.fn(), emit: (_command: { id: string; type?: number; params?: object }, _options?: { fromChangeset?: boolean }) => { void _command; void _options; } };
});
vi.mock('@univerjs/presets', () => ({
  ICommandService: 'commands', IUniverInstanceService: 'instances', DocumentFlavor: { MODERN: 0 }, LocaleType: { ZH_CN: 'zhCN' }, mergeLocales: () => ({}),
  createUniver: () => ({
    univer: { __getInjector: () => ({ get: (key: string) => key === 'commands' ? { onCommandExecuted: (callback: typeof sdk.emit) => { sdk.emit = callback; return { dispose: vi.fn() }; } } : { getUnit: () => ({ getSnapshot: sdk.rawRead }) } }), dispose: vi.fn() },
    univerAPI: { getActiveDocument: () => ({ save: sdk.save }), createDocument: (snapshot: unknown) => { sdk.create(snapshot); return { getId: () => 'doc', save: sdk.save, setSelection: vi.fn(), getPermission: () => ({ setReadOnly: vi.fn() }) }; } },
  }),
}));
vi.mock('@univerjs/preset-docs-core', () => ({ UniverDocsCorePreset: () => ({}), unmount: vi.fn(), DocBackScrollRenderController: {}, IRenderManagerService: {} }));
vi.mock('@univerjs/preset-docs-core/locales/zh-CN', () => ({ default: {} }));
vi.mock('./docs-features', () => ({ docsFeatureLocale: {}, docsFeaturePlugins: [], docsFeaturePresets: () => [] }));

beforeEach(() => {
  vi.clearAllMocks(); sdk.raw.body.dataStream = 'before\r\n';
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ width: 800, height: 600 } as DOMRect);
});
const source = { id: 'doc', body: { dataStream: 'before\r\n' }, snapshotFormatVersion: 5 };
const binding = { bindingId: 'binding', markerId: 'marker', syncDirection: 'TWO_WAY' } as TemplateBinding;

describe('Word change delivery uses raw body rather than exporting images', () => {
  it('preserves the live document when switching explicitly into read-only mode', async () => {
    const dirty = vi.fn(), ref = createRef<EditorHandle>();
    const view = render(<UniverDocsEditor ref={ref} snapshot={source} editable onDirty={dirty} />);
    await waitFor(() => expect(sdk.create).toHaveBeenCalledTimes(1));
    sdk.raw.body.dataStream = 'latest saved content\r\n';
    view.rerender(<UniverDocsEditor ref={ref} snapshot={source} editable={false} onDirty={dirty} />);
    await waitFor(() => expect(sdk.create).toHaveBeenCalledTimes(2));
    const restored = sdk.create.mock.calls[1]?.[0] as { body: { dataStream: string } } | undefined;
    expect(restored?.body.dataStream).toBe('latest saved content\r\n');
    expect(dirty).not.toHaveBeenCalled();
  });
  it('batches dirty and field notifications while recording every mutation immediately', async () => {
    const ref = createRef<EditorHandle>(), dirty = vi.fn(), value = vi.fn();
    render(<UniverDocsEditor ref={ref} snapshot={source} editable onDirty={dirty} onEditorValue={value} bindings={[binding]} />);
    await waitFor(() => expect(sdk.create).toHaveBeenCalledTimes(1)); sdk.rawRead.mockClear();
    act(() => {
      sdk.raw.body.dataStream = 'latest\r\n';
      for (let i = 0; i < 10; i++) sdk.emit({ id: 'doc.mutation.rich-text-editing', type: 2, params: { unitId: 'doc', actions: [{}] } });
      expect(ref.current?.getContentRevision?.()).toBe(10);
      expect(dirty).not.toHaveBeenCalled();
      ref.current?.flushChanges?.();
    });
    expect(dirty).toHaveBeenCalledTimes(1); expect(value).toHaveBeenCalledExactlyOnceWith(binding, 'latest');
    expect(sdk.rawRead).toHaveBeenCalledTimes(1); expect(sdk.save).not.toHaveBeenCalled();
    ref.current?.getSnapshot(); expect(sdk.save).toHaveBeenCalledTimes(1);
  });
  it('ignores cursor, permissions, other documents and restoration without touching snapshots', async () => {
    const ref = createRef<EditorHandle>(), dirty = vi.fn();
    render(<UniverDocsEditor ref={ref} snapshot={source} editable onDirty={dirty} />);
    await waitFor(() => expect(sdk.create).toHaveBeenCalledTimes(1));
    act(() => {
      sdk.emit({ id: 'doc.operation.set-selections', type: 1 });
      sdk.emit({ id: 'doc.mutation.permission', type: 2 });
      sdk.emit({ id: 'drawing.mutation.set-drawing', type: 2, params: { unitId: 'other' } });
      sdk.emit({ id: 'drawing.mutation.set-drawing', type: 2 }, { fromChangeset: true });
      ref.current?.flushChanges?.();
    });
    expect(ref.current?.getContentRevision?.()).toBe(0); expect(dirty).not.toHaveBeenCalled(); expect(sdk.save).not.toHaveBeenCalled();
  });
  it('does not deliver a queued image mutation after disposal', async () => {
    const ref = createRef<EditorHandle>(), dirty = vi.fn();
    const view = render(<UniverDocsEditor ref={ref} snapshot={source} editable onDirty={dirty} />);
    await waitFor(() => expect(sdk.create).toHaveBeenCalledTimes(1));
    act(() => sdk.emit({ id: 'drawing.mutation.set-drawing', type: 2, params: { unitId: 'doc' } }));
    view.unmount();
    act(() => sdk.emit({ id: 'drawing.mutation.set-drawing', type: 2, params: { unitId: 'doc' } }));
    expect(dirty).not.toHaveBeenCalled(); expect(sdk.save).not.toHaveBeenCalled();
  });
});
