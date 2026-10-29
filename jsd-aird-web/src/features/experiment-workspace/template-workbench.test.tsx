import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ExperimentTemplateDataPanel } from '@/pages/experiments/ExperimentTemplateDataPanel';
import { labSheet, type LabDefinition } from './lab-template';
import { experimentTemplateWorkbook } from './template-workbench';

const definition: LabDefinition = {
  version: 1,
  templateCode: 'EXP_TEST_UNIFIED',
  kind: 'TEST',
  firstSampleColumn: 2,
  initialLastSampleColumn: 7,
  regions: [{ id: 'film', label: '涂层性能', startRow: 0, endRow: 5 }],
  fields: {
    'sample.number': {
      fieldCode: 'sample.number',
      label: '样品编号',
      scope: 'SAMPLE',
      groupId: 'film',
      domain: 'BASIC',
      semantic: 'SOURCE_IDENTITY',
      resultKind: 'TEXT',
      unit: '',
    },
    adhesion: {
      fieldCode: 'adhesion',
      label: '附着力',
      scope: 'SAMPLE',
      groupId: 'film',
      domain: 'TEST',
      semantic: 'VALUE',
      resultKind: 'ADHESION',
      unit: '',
    },
  },
};
function snapshot(count = 8) {
  return {
    sheets: {
      sheet: {
        id: 'sheet',
        name: '测试实验',
        custom: { labTemplate: definition },
        columnData: Object.fromEntries(
          Array.from({ length: count }, (_, index) => [
            index + 2,
            { custom: { lab: { sampleKey: `group-${index}` } } },
          ]),
        ),
        cellData: {
          2: {
            1: { custom: { lab: { role: 'LABEL', fieldCode: 'sample.number' } } },
            ...Object.fromEntries(
              Array.from({ length: count }, (_, index) => [index + 2, { v: `T00${index + 1}` }]),
            ),
          },
          3: {
            1: { custom: { lab: { role: 'LABEL', fieldCode: 'adhesion' } } },
            2: { v: 0 },
            3: { v: '5B' },
          },
          4: {
            1: { v: 'MAT-001', custom: { lab: { role: 'MATERIAL_CODE', lineId: 'feed-1' } } },
            2: { v: '90g' },
          },
        },
      },
    },
  };
}
describe('existing data workbench contract for experiment templates', () => {
  it('keeps confirmation blocked for unmatched instance issues', () => {
    render(
      <ExperimentTemplateDataPanel
        embedded
        workbook={experimentTemplateWorkbook(snapshot(), true)}
        values={{}}
        editable
        confirmed={false}
        confirmationBlocked
        busy={false}
        onChange={vi.fn()}
        onConfirm={vi.fn()}
        onFocus={vi.fn()}
      />,
    );
    expect(screen.getByRole('button', { name: '完成数据核对' })).toBeDisabled();
  });
  it('exposes eight records, one indicator definition, original g input and valid zero grades', () => {
    const workbook = experimentTemplateWorkbook(snapshot(), true);
    expect(workbook.records).toHaveLength(8);
    expect(
      workbook.fieldDefinitions?.filter((field) => field.fieldCode === 'adhesion'),
    ).toHaveLength(1);
    expect(workbook.fields.find((field) => field.address === 'C4')).toMatchObject({
      effectiveValue: 0,
      valueStatus: 'VALID',
    });
    expect(workbook.fields.find((field) => field.address === 'C5')).toMatchObject({
      effectiveValue: '90g',
      unit: undefined,
    });
  });
  it('keeps compact result groups at their actual count', () => {
    const workbook = experimentTemplateWorkbook(snapshot(1), false);
    expect(workbook.records).toHaveLength(1);
    expect(workbook.fields.every((field) => !field.editable)).toBe(true);
  });
  it('uses spreadsheet dates and percentage formats in the shared field display', () => {
    const source: Record<string, unknown> = snapshot(1),
      sheet = labSheet(source)!;
    sheet.custom!.labTemplate = {
      ...definition,
      fields: {
        ...definition.fields,
        'basic.date': {
          ...definition.fields.adhesion!,
          fieldCode: 'basic.date',
          label: '日期',
          scope: 'EXPERIMENT',
        },
        solids: { ...definition.fields.adhesion!, fieldCode: 'solids', label: '固含', unit: '%' },
      },
    };
    sheet.cellData!['1'] = { 3: { v: 46319, custom: { lab: { fieldCode: 'basic.date' } } } };
    sheet.cellData!['5'] = {
      1: { custom: { lab: { fieldCode: 'solids' } } },
      2: { v: 0.6, s: 'percent' },
    };
    source.styles = { percent: { n: { pattern: '0.0%' } } };
    const workbook = experimentTemplateWorkbook(source, true);
    expect(workbook.fields.find((field) => field.fieldCode === 'basic.date')?.effectiveValue).toBe(
      '2026-10-24',
    );
    expect(workbook.fields.find((field) => field.fieldCode === 'solids')).toMatchObject({
      effectiveValue: 60,
      unit: '%',
    });
  });
  it('uses the shared data and structure browsers and writes only the selected comparison cell', async () => {
    const write = vi.fn().mockResolvedValue(undefined),
      focus = vi.fn();
    const { container } = render(
      <ExperimentTemplateDataPanel
        embedded
        workbook={experimentTemplateWorkbook(snapshot(), true)}
        values={{}}
        editable
        confirmed={false}
        busy={false}
        onChange={vi.fn()}
        onConfirm={vi.fn()}
        onFocus={vi.fn()}
        onWriteValue={write}
        onSelectField={focus}
        selectedCell={{ sheetId: 'sheet', sheetName: '测试实验', address: 'D4' }}
      />,
    );
    expect(container.querySelector('.data-field-data-browser')).toBeInTheDocument();
    const field = await screen.findByRole('button', { name: /附着力.*5B/ });
    fireEvent.click(field);
    const input = screen.getByRole('textbox', { name: '修正附着力' });
    fireEvent.change(input, {
      target: { value: '4B' },
    });
    fireEvent.blur(input);
    await waitFor(() =>
      expect(write).toHaveBeenCalledWith(
        expect.objectContaining({ address: 'D4', recordId: 'group-1' }),
        '4B',
      ),
    );
    expect(focus).toHaveBeenCalledWith(expect.objectContaining({ address: 'D4' }));
    fireEvent.click(screen.getByRole('tab', { name: '字段结构' }));
    expect(container.querySelector('.data-field-structure-browser')).toBeInTheDocument();
    expect(screen.getAllByText('附着力')).toHaveLength(1);
    expect(screen.queryByText('实验项目与对照组')).not.toBeInTheDocument();
  });
});
