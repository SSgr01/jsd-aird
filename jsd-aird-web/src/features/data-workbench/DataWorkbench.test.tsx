import { fireEvent, render, screen } from '@testing-library/react';

import type { DataFieldValueView, DataWorkbookSnapshot } from '@/services/data/data-api';

import {
  DataFieldCard,
  DataFieldDataBrowser,
  DataFieldStructureBrowser,
  DataWorkbenchShell,
} from './DataWorkbench';

const field: DataFieldValueView = {
  recordId: 'record-1',
  fieldCode: 'MATERIAL.NAME',
  fieldName: '物料名称',
  labelPath: '基本信息 / 物料名称',
  bindingId: 'binding-1',
  valuePath: '/material/name',
  valueSource: 'INPUT',
  valueStatus: 'VALID',
  valueType: 'TEXT',
  required: true,
  identity: true,
  trainingEligible: true,
  ragEligible: true,
  sheetId: 'sheet-1',
  sheetName: '原料数据',
  rowNumber: 3,
  address: 'B3',
  rawValue: '树脂 A',
  normalizedValue: '树脂 A',
  correctedValue: null,
  effectiveValue: '树脂 A',
  editable: true,
  excluded: false,
};

describe('DataWorkbench', () => {
  it('renders the shared spreadsheet and field panel shell', () => {
    render(<DataWorkbenchShell
      breadcrumb="数据中心 / 导入确认"
      title="原料数据.xlsx"
      canvas={<div>Excel 内容</div>}
      panel={<div>字段面板</div>}
    />);

    expect(screen.getByRole('region', { name: '原料数据.xlsx数据工作台' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Excel 工作区' })).toHaveTextContent('Excel 内容');
    expect(screen.getByText('字段面板')).toBeInTheDocument();
  });

  it('shows user-facing values and source location without internal identifiers', () => {
    render(<DataFieldCard field={field} active />);

    expect(screen.getByText('基本信息 / 物料名称')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /物料名称.*树脂 A/ })).toBeInTheDocument();
    expect(screen.getByText('原始值')).toBeInTheDocument();
    expect(screen.queryByText('标准化值')).not.toBeInTheDocument();
    expect(screen.queryByText('原料数据 · B3')).not.toBeInTheDocument();
    expect(screen.queryByText('binding-1')).not.toBeInTheDocument();
    expect(screen.queryByText('/material/name')).not.toBeInTheDocument();
    expect(screen.queryByText('无')).not.toBeInTheDocument();
  });

  it('explains how a staged field is confirmed instead of showing a generic warning', () => {
    render(<DataFieldCard field={{ ...field, valueStatus: 'STAGED' }} active />);

    expect(screen.getByText('待确认映射')).toBeInTheDocument();
    expect(screen.getByText('请先在“字段映射”中确认字段对应关系。')).toBeInTheDocument();
  });

  it('renders a template field once while two imported records browse their own values', () => {
    const workbook: DataWorkbookSnapshot = {
      fileName: '原料数据.xlsx',
      format: 'XLSX',
      snapshot: {},
      sheets: [],
      editable: true,
      regions: [{
        regionId: 'materials', name: '原料数据记录', structureType: 'ROW_TABLE',
        recordAxis: 'ROW', fieldCount: 1, recordCount: 2, fieldGroups: [],
      }],
      fieldDefinitions: [{
        componentId: 'materials', bindingId: 'binding-1', fieldCode: 'MATERIAL.NAME',
        displayName: '产品名称', mappingKind: 'REPEAT_FIELD', repeatAxis: 'ROW', valueType: 'TEXT',
        required: true, identity: true, sheetId: 'sheet-1', sourceRange: 'B2:B200',
      }],
      records: [
        { recordId: 'record-1', regionId: 'materials', label: '树脂 A', sequence: 1, excluded: false },
        { recordId: 'record-2', regionId: 'materials', label: '树脂 B', sequence: 2, excluded: false },
      ],
      fields: [
        { ...field, componentId: 'materials', recordGroupId: 'record-1', rawValue: '树脂 A', normalizedValue: '树脂 A', effectiveValue: '树脂 A' },
        { ...field, recordId: 'record-2', componentId: 'materials', recordGroupId: 'record-2', rawValue: '树脂 B', normalizedValue: '树脂 B', effectiveValue: '树脂 B', address: 'B4' },
      ],
    };

    const structure = render(<DataFieldStructureBrowser workbook={workbook} onSelectField={() => undefined} />);
    expect(screen.getAllByText('产品名称')).toHaveLength(1);
    expect(screen.queryByText('MATERIAL.NAME')).not.toBeInTheDocument();
    expect(screen.queryByText('B2:B200')).not.toBeInTheDocument();
    structure.unmount();

    let rerenderBrowser: (fieldKey?: string) => void = () => undefined;
    const browser = render(<DataFieldDataBrowser workbook={workbook} onSelectField={() => undefined} />);
    rerenderBrowser = (fieldKey) => browser.rerender(<DataFieldDataBrowser
      workbook={workbook}
      selectedFieldKey={fieldKey}
      onSelectField={(next) => rerenderBrowser([
        next.recordId, next.bindingId, next.valuePath, next.sheetId, next.address,
      ].join('|'))}
    />);
    rerenderBrowser();
    expect(screen.getByRole('button', { name: /物料名称.*树脂 A/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /物料名称.*树脂 B/ })).not.toBeInTheDocument();
    fireEvent.click(screen.getByText('树脂 B'));
    expect(screen.getByRole('button', { name: /物料名称.*树脂 B/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /物料名称.*树脂 B/ }));
    expect(screen.getByText('原始值')).toBeInTheDocument();
    expect(screen.getAllByText('树脂 B').length).toBeGreaterThan(1);
  });

  it('shows every current value at a glance and removes duplicate one-field grouping noise', () => {
    const workbook: DataWorkbookSnapshot = {
      fileName: '检验数据.xlsx',
      format: 'XLSX',
      snapshot: {},
      sheets: [],
      editable: true,
      regions: [{
        regionId: 'detail', name: '明细数据', structureType: 'ROW_TABLE',
        recordAxis: 'ROW', fieldCount: 2, recordCount: 1, fieldGroups: [],
      }],
      fieldDefinitions: [
        { componentId: 'detail', bindingId: 'binding-approval', fieldCode: 'APPROVAL', displayName: '制 定', labelPath: '制_定', groupPath: '制_定', mappingKind: 'REPEAT_FIELD', valueType: 'TEXT', required: false, identity: false },
        { componentId: 'detail', bindingId: 'binding-acid', fieldCode: 'ACID', displayName: '酸值', groupPath: '检测指标', mappingKind: 'REPEAT_FIELD', valueType: 'NUMBER', unit: 'mg KOH/g', required: true, identity: false },
      ],
      records: [{ recordId: 'record-1', regionId: 'detail', label: '第 1 条记录', sequence: 1, excluded: false }],
      fields: [
        { ...field, componentId: 'detail', recordGroupId: 'record-1', bindingId: 'binding-approval', fieldCode: 'APPROVAL', fieldName: '制 定', labelPath: '制_定', groupPath: '制_定', rawValue: '', normalizedValue: '', effectiveValue: '', required: false },
        { ...field, componentId: 'detail', recordGroupId: 'record-1', bindingId: 'binding-acid', fieldCode: 'ACID', fieldName: '酸值', labelPath: '检测指标 / 酸值', groupPath: '检测指标', rawValue: 12.5, normalizedValue: 12.5, effectiveValue: 12.5, valueType: 'NUMBER', unit: 'mg KOH/g' },
      ],
    };

    render(<DataFieldDataBrowser workbook={workbook} onSelectField={() => undefined} />);

    expect(screen.getByText('制定')).toBeInTheDocument();
    expect(screen.queryByText('制 定')).not.toBeInTheDocument();
    expect(screen.queryByText('制_定')).not.toBeInTheDocument();
    expect(screen.getByText('未填写')).toBeInTheDocument();
    expect(screen.getByText('12.5')).toBeInTheDocument();
    expect(screen.getByText('mg KOH/g')).toBeInTheDocument();
    expect(screen.getByText('当前记录 2 个字段全部校验通过')).toBeInTheDocument();
    expect(screen.queryByText('校验通过')).not.toBeInTheDocument();
    expect(screen.queryByText('1 项')).not.toBeInTheDocument();
  });

  it('keeps conversion and correction details behind the selected field row', () => {
    render(<DataFieldCard field={{
      ...field,
      rawValue: '12,5',
      normalizedValue: 12.5,
      correctedValue: 13,
      effectiveValue: 13,
      unit: 'mg KOH/g',
    }} active />);

    expect(screen.getByRole('button', { name: /物料名称.*13.*mg KOH\/g.*已修正/ })).toBeInTheDocument();
    expect(screen.getByText('格式转换值')).toBeInTheDocument();
    expect(screen.getByText('最终采用值')).toBeInTheDocument();
    expect(screen.getByText('该字段已人工修正')).toBeInTheDocument();
  });
});
