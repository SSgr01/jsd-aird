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
    expect(screen.getAllByText('树脂 A')).toHaveLength(1);
    expect(screen.queryByText('标准化值')).not.toBeInTheDocument();
    expect(screen.queryByText('原料数据 · B3')).not.toBeInTheDocument();
    expect(screen.queryByText('binding-1')).not.toBeInTheDocument();
    expect(screen.queryByText('/material/name')).not.toBeInTheDocument();
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
    expect(screen.getAllByText('树脂 A').length).toBeGreaterThan(0);
    expect(screen.queryByText('树脂 B', { selector: 'dd' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '树脂 B' }));
    fireEvent.click(screen.getByRole('button', { name: /物料名称/ }));
    expect(screen.getByText('树脂 B', { selector: 'dd' })).toBeInTheDocument();
  });
});
