import { Alert, App, Button, Collapse, Form, Input, InputNumber, Modal, Select, Space, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { spectrumApi, type SpectrumMethodCatalog, type SpectrumMethodField, type SpectrumTestMethodSnapshot } from '@/services/spectrum/spectrum-api';

import { applyMethodPreset, conditionNotices, emptyMethodSnapshot } from './spectrum-test-conditions';

export function SpectrumTestConditions({ categoryCode, value, onChange, legacyText, onLegacyTextChange, resetToken = 0 }: {
  categoryCode?: string; value?: SpectrumTestMethodSnapshot;
  onChange: (value: SpectrumTestMethodSnapshot) => void; legacyText?: string; onLegacyTextChange?: (text: string) => void; resetToken?: number;
}) {
  const { message } = App.useApp();
  const [catalog, setCatalog] = useState<SpectrumMethodCatalog>();
  const [error, setError] = useState('');
  const [manager, setManager] = useState(false);
  const [presetName, setPresetName] = useState('');
  const [busy, setBusy] = useState(false);
  const previousReset = useRef(resetToken);
  const load = () => spectrumApi.methodPresets().then(setCatalog);
  useEffect(() => { let live = true; spectrumApi.methodPresets().then(data => { if (live) setCatalog(data); }).catch(e => { if (live) setError(e instanceof Error ? e.message : '方法预设加载失败'); }); return () => { live = false; }; }, []);
  const code = value?.methodType ?? categoryCode ?? '';
  const method = catalog?.methods.find(item => item.code === code);
  const snapshot = value ?? emptyMethodSnapshot(code, legacyText);
  useEffect(() => {
    if (previousReset.current === resetToken) return;
    previousReset.current = resetToken;
    if (!value || !method) return;
    const next = structuredClone(value);
    for (const field of method.fields) if (field.variable) delete next.conditions[field.key];
    if (method.code === 'UV') next.conditions.blankReference = '与溶剂一致';
    onChange(next);
  }, [resetToken, method, value, onChange]);
  const patch = (field: SpectrumMethodField, next: unknown) => {
    const target = field.target ?? 'conditions';
    const values = { ...snapshot[target] };
    if (next == null || next === '') delete values[field.key]; else values[field.key] = next;
    onChange({ ...snapshot, [target]: values });
  };
  const fieldInput = (field: SpectrumMethodField) => {
    if (field.when && snapshot.conditions.dispersionTreatment !== field.when) return null;
    const current = snapshot[field.target ?? 'conditions'][field.key];
    if (field.kind === 'program' || field.kind === 'specimens') {
      const rows = Array.isArray(current) ? current as Record<string, unknown>[] : [];
      const columns = field.kind === 'program'
        ? [{ key: 'temperature', label: '目标温度（℃）' }, { key: 'rate', label: '升温速率（℃/min）' }, { key: 'hold', label: '保持（min）' }]
        : [{ key: 'name', label: '试样编号' }, { key: 'width', label: '实测宽（mm）' }, { key: 'thickness', label: '实测厚（mm）' }];
      return <Form.Item key={field.key} label={field.label}>
        {field.kind === 'program' && <Typography.Paragraph type="secondary">第一行为初始温度和保持时间，后续逐段填写。未知项留空。</Typography.Paragraph>}
        {rows.map((row, index) => <Space key={index} wrap style={{ marginBottom: 8 }}>
          <span>{index + 1}</span>{columns.map(column => column.key === 'name'
            ? <Input key={column.key} aria-label={`${field.label}第${index + 1}行${column.label}`} placeholder={column.label} style={{ width: 130 }} value={row[column.key] as string} maxLength={160} onChange={e => patch(field, rows.map((item, i) => i === index ? { ...item, [column.key]: e.target.value } : item))} />
            : <InputNumber key={column.key} aria-label={`${field.label}第${index + 1}行${column.label}`} placeholder={column.label} style={{ width: 145 }} value={row[column.key] as number | undefined} min={column.key === 'temperature' ? -273.15 : 0} onChange={v => patch(field, rows.map((item, i) => i === index ? Object.fromEntries(Object.entries({ ...item, [column.key]: v }).filter(([, value]) => value != null)) : item))} />)}
          <Button size="small" onClick={() => patch(field, rows.filter((_, i) => i !== index))}>移除</Button>
        </Space>)}
        <Button size="small" disabled={rows.length >= 50} onClick={() => patch(field, [...rows, {}])}>{field.kind === 'program' ? '增加温程段' : '增加试样'}</Button>
      </Form.Item>;
    }
    const label = `${field.label}${field.unit ? `（${field.unit}）` : ''}`;
    return <Form.Item key={field.key} label={label}>
      {field.options ? <Select aria-label={label} allowClear value={current as string | undefined} options={field.options.map(option => ({ value: option, label: option }))} onChange={v => patch(field, v)} placeholder="可选" />
        : field.kind === 'number' || field.kind === 'integer' ? <InputNumber aria-label={label} style={{ width: '100%' }} min={field.min} max={field.max} precision={field.kind === 'integer' ? 0 : undefined} value={current as number | undefined} onChange={v => patch(field, v)} placeholder="可选" />
          : <Input aria-label={label} maxLength={500} placeholder={field.placeholder ?? '可选'} value={current as string ?? ''} onChange={e => patch(field, e.target.value)} />}
    </Form.Item>;
  };
  const presets = catalog?.presets.filter(item => item.methodType === code) ?? [];
  const selectedPreset = presets.find(item => item.id === snapshot.presetId);
  const presetOptions = presets.map(item => ({ value: item.id, label: `${item.name} · V${item.version}${item.active ? '' : '（已停用）'}`, disabled: !item.active }));
  if (snapshot.presetId && !selectedPreset) presetOptions.push({ value: snapshot.presetId, label: `${snapshot.presetName ?? '历史预设'} · V${snapshot.presetRevision ?? '?'}（当前不可选）`, disabled: true });
  const savePreset = async (update: boolean, active = update ? selectedPreset?.active ?? true : true, toggleOnly = false) => {
    if (!method) return;
    setBusy(true);
    try {
      const saved = await spectrumApi.saveMethodPreset({ name: toggleOnly ? selectedPreset?.name ?? '' : presetName.trim() || selectedPreset?.name || '', snapshot: toggleOnly && selectedPreset ? selectedPreset.snapshot : snapshot, active,
        ...(update && selectedPreset ? { revision: selectedPreset.revision } : {}) }, update ? selectedPreset?.id : undefined);
      await load();
      if (!toggleOnly) onChange({ ...snapshot, presetId: saved.id, presetRevision: String(saved.version), presetName: saved.name });
      void message.success(toggleOnly ? active ? '预设已启用，已有图谱不变' : '预设已停用，已有图谱不变' : '预设已保存，其他图谱的条件不变');
      setManager(false);
    } catch (e) { void message.error(e instanceof Error ? e.message : '预设保存失败'); } finally { setBusy(false); }
  };
  return <section aria-label="图谱测试条件">
    {error && <Alert type="warning" message={error} action={<Button size="small" onClick={() => { void load().then(() => setError('')).catch(() => undefined); }}>重试</Button>} />}
    <Form.Item label="测试方法"><Select aria-label="测试方法" value={method?.code} allowClear={false} placeholder="选择测试方法" options={catalog?.methods.map(item => ({ value: item.code, label: item.name }))} onChange={next => onChange(emptyMethodSnapshot(next, snapshot.legacyText ?? legacyText))} /></Form.Item>
    {method && <>
      <Form.Item label="仪器／方法预设"><Select aria-label="仪器／方法预设" allowClear value={snapshot.presetId} placeholder="可不选，直接填写当次条件" options={presetOptions}
        labelRender={({ label }) => snapshot.presetRevision ? `${snapshot.presetName ?? selectedPreset?.name ?? '历史预设'} · V${snapshot.presetRevision}${!selectedPreset ? '（当前不可选）' : !selectedPreset.active ? '（已停用）' : ''}` : label} onChange={id => {
        const preset = presets.find(item => item.id === id);
        onChange(preset ? applyMethodPreset(preset, method, snapshot.legacyText ?? legacyText) : { ...snapshot, presetId: undefined, presetRevision: undefined, presetName: undefined });
      }} />{snapshot.presetRevision && <Typography.Text type="secondary">本次采用 V{snapshot.presetRevision}；修改预设不会改变本记录。</Typography.Text>}</Form.Item>
      {method.fields.filter(field => field.variable).map(fieldInput)}
      <Collapse items={[{ key: 'method', label: '仪器与固定设置', children: method.fields.filter(field => !field.variable).map(fieldInput) }, {
        key: 'notes', label: '方法详情／补充说明', children: <><Input.TextArea aria-label="方法详情／补充说明" value={snapshot.notes ?? ''} maxLength={6000} rows={4} placeholder="校准、光学参数、参考压力、原报告待核对值；多方法文件注明对应页／样品" onChange={e => onChange({ ...snapshot, notes: e.target.value })} />
          {(snapshot.legacyText || legacyText) && <Form.Item label="原测试条件（保留原文，不自动拆解）"><Input.TextArea aria-label="原测试条件" rows={3} maxLength={10000} value={snapshot.legacyText ?? legacyText} onChange={e => onChange({ ...snapshot, legacyText: e.target.value })} /></Form.Item>}</>,
      }]} />
      {conditionNotices(snapshot, method).map(text => <Typography.Paragraph key={text} type="secondary" style={{ marginTop: 8, marginBottom: 4 }}>{text}</Typography.Paragraph>)}
      {typeof snapshot.conditions.rangeMin === 'number' && typeof snapshot.conditions.rangeMax === 'number' && snapshot.conditions.rangeMin >= snapshot.conditions.rangeMax && <Alert type="warning" message="扫描下限必须小于上限，请核对后保存。" />}
      {catalog?.canManage && <Button size="small" onClick={() => { setPresetName(selectedPreset?.name ?? ''); setManager(true); }}>管理方法预设</Button>}
      <Modal open={manager} title="保存仪器／方法预设" footer={null} onCancel={() => setManager(false)} destroyOnHidden>
        <Typography.Paragraph>保存当前仪器、固定设置和方法说明；样品介质、浓度等变化项不会保存为预设。</Typography.Paragraph>
        <Input aria-label="预设名称" placeholder="预设名称" maxLength={160} value={presetName} onChange={e => setPresetName(e.target.value)} />
        <Space wrap style={{ marginTop: 16 }}>
          <Button type="primary" loading={busy} disabled={!presetName.trim()} onClick={() => { void savePreset(false); }}>另存新预设</Button>
          {selectedPreset && <><Button loading={busy} disabled={!presetName.trim()} onClick={() => { void savePreset(true); }}>更新所选预设</Button><Button loading={busy} onClick={() => { void savePreset(true, !selectedPreset.active, true); }}>{selectedPreset.active ? '停用所选预设' : '启用所选预设'}</Button></>}
        </Space>
      </Modal>
    </>}
    {!method && <Input.TextArea aria-label="测试条件补充说明" value={legacyText ?? ''} rows={3} maxLength={10000} readOnly={!onLegacyTextChange} placeholder="可先填写原文说明，方法明确后再逐项登记" onChange={e => onLegacyTextChange?.(e.target.value)} />}
  </section>;
}
