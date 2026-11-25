import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from 'antd';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { SpectrumTestConditions } from './SpectrumTestConditions';
import { applyMethodPreset, conditionNotices, emptyMethodSnapshot } from './spectrum-test-conditions';
import type { SpectrumMethodCatalog, SpectrumMethodSchema, SpectrumTestMethodSnapshot } from '@/services/spectrum/spectrum-api';

const api = vi.hoisted(() => ({ catalog: vi.fn(), save: vi.fn() }));
vi.mock('@/services/spectrum/spectrum-api', () => ({ spectrumApi: { methodPresets: api.catalog, saveMethodPreset: api.save } }));
const methods = JSON.parse(readFileSync(resolve(process.cwd(), '../jsd-aird-api/src/main/resources/spectrum-methods.json'), 'utf8')) as SpectrumMethodSchema[];
const preset = { id: 'preset', revision: 'rev', name: 'UV 预设', methodType: 'UV', version: 1, active: true,
  snapshot: { ...emptyMethodSnapshot('UV'), instrument: { model: 'UV-1900i' }, conditions: { solvent: '上次溶剂', concentration: '上次浓度', pathLength: 10 } } };
const catalog: SpectrumMethodCatalog = { methods, presets: [preset], canManage: false };

describe('spectrum method conditions', () => {
  it('copies fixed settings, clears variable sample values and freezes the selected version', () => {
    const snapshot = applyMethodPreset(preset, methods.find(method => method.code === 'UV')!, '旧文字');
    expect(snapshot.conditions).toEqual({ pathLength: 10, blankReference: '与溶剂一致' });
    expect(snapshot.legacyText).toBe('旧文字');
    expect(snapshot.presetRevision).toBe('1');
    snapshot.instrument.model = '本次覆盖';
    expect(preset.snapshot.instrument.model).toBe('UV-1900i');
  });
  it.each(methods)('renders only the $name variable fields and keeps fixed settings collapsed', async method => {
    api.catalog.mockResolvedValue(catalog);
    const { unmount } = render(<App><SpectrumTestConditions categoryCode={method.code} onChange={vi.fn()} /></App>);
    await waitFor(() => expect(screen.getByText('仪器与固定设置')).toBeInTheDocument());
    for (const field of method.fields.filter(field => field.variable && !field.when && !['specimens', 'program'].includes(field.kind ?? ''))) {
      expect(screen.getByText(`${field.label}${field.unit ? `（${field.unit}）` : ''}`)).toBeInTheDocument();
    }
    expect(screen.queryByRole('button', { name: '管理方法预设' })).not.toBeInTheDocument();
    if (method.code !== 'PARTICLE_SIZE') expect(screen.queryByText('分散介质及混合比例')).not.toBeInTheDocument();
    unmount();
  });
  it('preserves old free text and resets sample values after a batch', async () => {
    api.catalog.mockResolvedValue(catalog);
    function Harness({ resetToken }: { resetToken: number }) {
      const [value, setValue] = useState<SpectrumTestMethodSnapshot>({ ...emptyMethodSnapshot('UV', '原文字'), conditions: { solvent: 'PM', concentration: '1 mg/mL', pathLength: 10 } });
      return <App><SpectrumTestConditions value={value} onChange={setValue} resetToken={resetToken} /><output>{JSON.stringify(value)}</output></App>;
    }
    const view = render(<Harness resetToken={0} />);
    await waitFor(() => expect(screen.getByDisplayValue('PM')).toBeInTheDocument());
    act(() => view.rerender(<Harness resetToken={1} />));
    await waitFor(() => expect(screen.queryByDisplayValue('PM')).not.toBeInTheDocument());
    expect(screen.getByRole('status').textContent).toContain('原文字');
    expect(screen.getByRole('status').textContent).toContain('"pathLength":10');
    fireEvent.click(screen.getByText('方法详情／补充说明'));
    expect(screen.getByDisplayValue('原文字')).toBeInTheDocument();
  });
  it('explains the relevant comparison gaps without guessing a UV range or GC ramp', () => {
    const uv = { ...emptyMethodSnapshot('UV'), conditions: { solvent: 'PM', rangeMin: 110 } };
    expect(conditionNotices(uv, methods.find(method => method.code === 'UV')!).join()).toContain('低于常规');
    expect(uv.conditions.rangeMin).toBe(110);
    expect(conditionNotices(emptyMethodSnapshot('GC'), methods.find(method => method.code === 'GC')!).join()).toContain('不能从起终温');
  });
});
