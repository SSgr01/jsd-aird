import { fireEvent, render, screen, within, waitFor } from '@testing-library/react';
import { useState, type ComponentProps } from 'react';
import { describe, expect, it, vi } from 'vitest';
import type { BusinessField, EditorSelection, ExperimentImportConfiguration, FieldModel, TemplateBinding } from '@/features/template-workspace/types';
import { FieldStructure, TemplateFieldManager, type FieldManagerTab } from './TemplateFieldManager';
import { InstanceFieldEditor as InstanceEditor } from '@/features/template-workspace/InstanceFieldEditor';
import { createWorkbookInstance } from '@/features/template-workspace/workbook-instance';
import type { WorkbookTemplateInstance } from '@/features/template-workspace/workbook-instance';
function InstanceFieldEditor(props: Omit<ComponentProps<typeof InstanceEditor>, 'view' | 'onViewChange'> & { initialView?: 'data' | 'structure' | 'properties' }) {
  const [view,setView] = useState<'data' | 'structure' | 'properties'>(props.initialView ?? 'structure');
  return <><div role="tablist" aria-label="字段面板">
    {(['data','structure','properties'] as const).map((key,index) => <button key={key} role="tab" aria-selected={view === key} onClick={() => setView(key)}>{['字段数据','字段结构','字段属性'][index]}</button>)}
  </div><InstanceEditor {...props} view={view} onViewChange={setView} /></>;
}

const field = (id: string, name: string, groupId: string): BusinessField => ({
  id, name, groupId, kind: 'SCALAR', valueType: 'string', required: false, reviewStatus: 'CONFIRMED',
});
const total = { ...field('total', '用量合计（g）', 'formula'), unit: 'g', experimentField: { domain: 'FORMULA' as const, field: 'ACTUAL_QTY' } };
const model: FieldModel = {
  modelVersion: 1,
  groups: [{ id: 'basic', name: '基础信息', order: 0 }, { id: 'conditions', name: '实验条件', order: 1 }, { id: 'formula', name: '实验配方', order: 2 }],
  fields: [field('title', '实验题目', 'basic'), field('substrate', '实验素材', 'conditions'), total],
  blocks: [], semanticAnnotations: [],
};
const configuration: ExperimentImportConfiguration = {
  templateUsage: 'EXPERIMENT_DATA', identities: [],
  listProjections: [{ listProjectionId: 'materials', domain: 'FORMULA', componentId: 'comparison',
    recordAxis: 'COLUMN', itemAxis: 'ROW', labelRange: 'B52:B59', valueRange: 'C52:H59',
    labelSemantic: 'MATERIAL_CODE', valueSemantic: 'ACTUAL_QTY' }],
};
const handlers = { onSelectField: vi.fn(), onAddField: vi.fn(), onAddStructuredField: vi.fn(), onManageGroups: vi.fn() };
const mapping: TemplateBinding[] = [{
  bindingId: 'comparison', componentId: 'comparison', dataPath: '/samples', role: 'REPEAT_REGION',
  mappingKind: 'REPEAT_REGION', repeatAxis: 'COLUMN', locatorType: 'TABLE_REGION',
  locator: { sheetId: 's', sheetName: '测试实验', dataRange: 'C7:H135' },
  syncDirection: 'TWO_WAY', primaryBinding: true, bindingStatus: 'VALID',
}];
function Workspace({ selection, onFocusBinding, editable = false }: {
  selection?: EditorSelection; onFocusBinding: (binding: TemplateBinding) => void; editable?: boolean;
}) {
  const [tab, setTab] = useState<FieldManagerTab>('structure');
  const [configurationValue, setConfiguration] = useState(configuration);
  return <TemplateFieldManager editable={editable} format="XLSX" fieldModel={model} mapping={mapping}
    activeTab={tab} onActiveTabChange={setTab} selection={selection} onFocusBinding={onFocusBinding}
    experimentConfiguration={configurationValue} onExperimentConfigurationChange={setConfiguration}
    {...handlers} onUpdateField={vi.fn()} onUpdateCoordinates={vi.fn()} onPickCoordinate={vi.fn()}
    onPickProjectionRange={vi.fn()} onDeleteField={vi.fn()} onPlaceWordField={vi.fn()}
    onSelectRecognitionItem={vi.fn()} onConfirmRecognitionItem={vi.fn()} onModifyRecognitionItem={vi.fn()}
    onIgnoreRecognitionItem={vi.fn()} onRestoreRecognitionItem={vi.fn()} onSelectQualityIssue={vi.fn()}
    onApplyQualityIssue={vi.fn()} onIgnoreQualityIssue={vi.fn()} onRollbackQualityIssue={vi.fn()} />;
}

describe('field structure for configured templates', () => {
  it('shows an explicit empty state when properties are opened before selecting a field', () => {
    render(<InstanceFieldEditor initialView="properties" instance={createWorkbookInstance(model,mapping,configuration)} ownerId="empty" editable={false} onChange={vi.fn()} onFocus={vi.fn()} />);
    expect(screen.getByText('请先在字段结构或表格中选择字段')).toBeInTheDocument();
  });
  it('has one navigation level and preserves the mounted structure while editing properties', () => {
    const { container } = render(<InstanceFieldEditor instance={createWorkbookInstance(model,mapping,configuration)} ownerId="flat" editable={false} onChange={vi.fn()} onFocus={vi.fn()} />);
    expect(screen.getAllByRole('tablist')).toHaveLength(1);
    expect(screen.getAllByRole('tab')).toHaveLength(3);
    expect(screen.queryByRole('tab',{ name:'字段列表' })).not.toBeInTheDocument();
    const structure = container.querySelector('.field-definition-pane')!;
    structure.scrollTop = 140;
    fireEvent.click(screen.getByRole('button',{ name:/用量 明细字段/ }));
    expect(screen.getByRole('tab',{ name:'字段属性' })).toHaveAttribute('aria-selected','true');
    expect(screen.getByRole('textbox',{ name:'单位' })).toHaveValue('g');
    expect(screen.queryByText('系统已识别为')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab',{ name:'字段结构' }));
    expect(container.querySelector('.field-definition-pane')).toBe(structure);
    expect(structure.scrollTop).toBe(140);
    expect(screen.getByRole('button',{ name:/用量 明细字段/ })).toHaveAttribute('aria-current','true');
  });
  it('does not leave data browsing when a sheet cell is selected', async () => {
    render(<InstanceFieldEditor initialView="data" instance={createWorkbookInstance(model,mapping,configuration)} ownerId="data" editable={false} onChange={vi.fn()} onFocus={vi.fn()}
      selection={{ sheetId:'s',sheetName:'原表',address:'D54' }} />);
    await waitFor(() => expect(screen.getByRole('tab',{ name:'字段数据' })).toHaveAttribute('aria-selected','true'));
    fireEvent.click(screen.getByRole('tab',{ name:'字段属性' }));
    expect(screen.getByRole('textbox',{ name:'字段名称' })).toHaveValue('用量');
  });
  it('removes the experiment configuration entry while retaining recipe definitions', () => {
    render(<Workspace onFocusBinding={vi.fn()} />);
    expect(screen.queryByRole('tab',{ name:'实验数据设置' })).not.toBeInTheDocument();
    expect(screen.getByRole('button',{ name:/物料编码 明细字段/ })).toBeInTheDocument();
  });
  it('inherits business grouping through a geometry root when adding an instance field', () => {
    const child = { ...field('substrate', '实验素材', 'conditions'), bindingId: 'substrate' };
    const childBinding: TemplateBinding = { ...mapping[0]!, bindingId: 'substrate', parentBindingId: 'comparison', role: 'FIELD', mappingKind: 'REPEAT_FIELD', locator: { sheetId: 's', labelRange:'B8', address: 'C8:H8' } };
    const instance = createWorkbookInstance({ ...model, fields: [child] }, [...mapping, childBinding], configuration);
    // Server-seeded versions need not carry a redundant parentFieldId on every child.
    delete instance.model.fields.find(f => f.id === child.id)!.parentFieldId;
    const changed = vi.fn<(instance: WorkbookTemplateInstance) => void>();
    render(<InstanceFieldEditor instance={instance} ownerId="test" editable onChange={changed} onFocus={vi.fn()} selection={{ sheetId:'s',sheetName:'测试实验',address:'B8' }} />);
    fireEvent.click(screen.getByRole('tab', { name: '字段结构' }));
    fireEvent.click(screen.getByRole('button', { name: /新增字段/ }));
    const added = changed.mock.lastCall?.[0].model.fields.find(f => f.fieldOrigin === 'INSTANCE_LOCAL');
    expect(added).toMatchObject({ groupId: 'conditions', fieldType: 'TABLE_COLUMN', mappingKind: 'REPEAT_FIELD', pathSegments: ['实验条件', '新字段'] });
    expect(added).toMatchObject({ experimentSemanticStatus: 'AUTO_CONFIRMED', experimentField: { domain: 'OTHER', field: 'DYNAMIC_VALUE' } });
  });
  it('keeps geometry roots out of business grouping and labels omitted results', () => {
    const root: BusinessField = { ...field('root', '对照组区域', 'conditions'), kind: 'COLUMN_TABLE', displayRole: 'REGION', structuralOnly: true };
    const children = model.fields.map(f => ({ ...f, parentFieldId: root.id }));
    render(<FieldStructure editable={false} fieldModel={{ ...model, fields: [root, ...children] }} {...handlers} hiddenFieldIds={new Set(['title'])} />);
    expect(screen.getByText(/区域 3 · 字段 3/)).toBeInTheDocument();
    expect(screen.queryByText('对照组区域')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /实验题目/ })).toHaveTextContent('结果未展示');
  });

  it('opens hidden field properties without focusing the wrong cell', () => {
    const focus = vi.fn();
    render(<InstanceFieldEditor instance={createWorkbookInstance(model, mapping, configuration)} editable={false} ownerId="test" onChange={vi.fn()} onFocus={focus} hiddenFieldIds={new Set(['title'])} />);
    fireEvent.click(screen.getByRole('button', { name: /实验题目/ }));
    expect(screen.getByText('本次结果未展示')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '定位字段' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '返回字段结构' })).not.toBeInTheDocument();
    expect(focus).not.toHaveBeenCalled();
    expect(screen.getByRole('textbox', { name: '字段名称' })).toBeDisabled();
  });
  it('shows business groups and empty recipe definitions without creating material-specific fields', () => {
    const onSelectProjection = vi.fn();
    render(<FieldStructure editable={false} fieldModel={model} {...handlers}
      experimentConfiguration={configuration} onSelectProjection={onSelectProjection} />);
    expect(screen.queryByText('未分区字段')).not.toBeInTheDocument();
    expect(screen.getByText(/区域 3 · 字段 5/)).toBeInTheDocument();
    const group = screen.getByText('实验配方').closest('details');
    expect(group).not.toBeNull();
    expect(within(group!).getByRole('button', { name: /物料编码/ })).toHaveTextContent('B52:B59');
    const quantity = within(group!).getByRole('button', { name: /用量 明细字段/ });
    expect(quantity).toHaveTextContent('g · C52:H59');
    fireEvent.click(quantity);
    expect(onSelectProjection).toHaveBeenCalledWith(configuration.listProjections[0], 'valueRange');
    expect(model.fields).toHaveLength(3);
  });

  it('does not attach unrelated fields to a root with no block ID', () => {
    const root: BusinessField = { ...field('root', '对照组区域', 'conditions'), kind: 'COLUMN_TABLE', displayRole: 'REGION' };
    const { container } = render(<FieldStructure editable={false} fieldModel={{ ...model, fields: [root, ...model.fields] }} {...handlers} />);
    const rootView = container.querySelector('details');
    expect(rootView).not.toBeNull();
    expect(within(rootView!).queryByRole('button', { name: /实验题目/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /实验题目/ })).toBeInTheDocument();
    expect(screen.getByText('基础信息')).toBeInTheDocument();
  });

  it('keeps genuinely unassigned fields visible for review', () => {
    render(<FieldStructure editable={false} fieldModel={{ ...model, fields: [field('unknown', '未知项目', 'missing')] }} {...handlers} />);
    expect(screen.getByText('未分区字段')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /未知项目/ })).toBeInTheDocument();
  });

  it('uses the ordinary properties and focuses the recipe binding; cell selections switch both logical fields', async () => {
    const onFocusBinding = vi.fn<(binding: TemplateBinding) => void>();
    const { rerender } = render(<Workspace onFocusBinding={onFocusBinding} />);
    fireEvent.click(screen.getByRole('button', { name: /用量 明细字段/ }));
    expect(screen.getByRole('textbox', { name: '字段名称' })).toHaveValue('用量');
    expect(screen.getByRole('textbox', { name: '单位' })).toHaveValue('g');
    expect(screen.getByRole('textbox', { name: /^填写位置/ })).toHaveValue('C52:H59');
    expect(onFocusBinding.mock.lastCall?.[0].locator).toMatchObject({ address: 'C52:H59', sheetId: 's' });
    rerender(<Workspace onFocusBinding={onFocusBinding} selection={{ sheetId: 's', sheetName: '测试实验', address: 'B54' }} />);
    await waitFor(() => expect(screen.getByRole('textbox', { name: '字段名称' })).toHaveValue('物料编码'));
    expect(screen.getByRole('textbox', { name: /^填写位置/ })).toHaveValue('B52:B59');
    rerender(<Workspace onFocusBinding={onFocusBinding} selection={{ sheetId: 's', sheetName: '测试实验', address: 'F55' }} />);
    await waitFor(() => expect(screen.getByRole('textbox', { name: '字段名称' })).toHaveValue('用量'));
    fireEvent.click(screen.getByRole('tab', { name: '字段结构' }));
    expect(screen.getByRole('button', { name: /用量 明细字段/ })).toHaveAttribute('aria-current', 'true');
  });

  it('saves list coordinate edits through the same field properties without creating scalar data bindings', () => {
    const onChange = vi.fn<(instance: WorkbookTemplateInstance) => void>();
    const instance = createWorkbookInstance(model, mapping, configuration);
    render(<InstanceFieldEditor editable instance={instance} ownerId="test" onChange={onChange} onFocus={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: /用量 明细字段/ }));
    const range = screen.getByRole('textbox', { name: /^填写位置/ });
    fireEvent.change(range, { target: { value: ' $c$52:$j$61 ' } });
    fireEvent.blur(range);
    expect(onChange.mock.lastCall?.[0].execution?.listProjections[0]?.valueRange).toBe('C52:J61');
    expect(onChange.mock.lastCall?.[0].mapping).toEqual(instance.mapping);
    onChange.mockClear();
    fireEvent.change(range, { target: { value: '52C' } });
    fireEvent.blur(range);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByRole('textbox', { name: '单位' })).toBeDisabled();
  });

  it('follows cell selection in an instance and permits returning to its field structure', async () => {
    const instance = createWorkbookInstance(model, mapping, configuration);
    render(<InstanceFieldEditor editable={false} instance={instance} ownerId="test" onChange={vi.fn()} onFocus={vi.fn()}
      selection={{ sheetId: 's', sheetName: '测试实验', address: 'D54' }} />);
    await waitFor(() => expect(screen.getByRole('textbox', { name: '字段名称' })).toHaveValue('用量'));
    fireEvent.click(screen.getByRole('tab', { name: '字段结构' }));
    expect(screen.getByRole('button', { name: /物料编码 明细字段/ })).toBeInTheDocument();
  });
});
