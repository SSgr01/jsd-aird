import { fireEvent, render, screen } from '@testing-library/react';
import { vi } from 'vitest';

import { ExperimentFieldReviewPanel } from './ExperimentFieldReviewPanel';

it('uses the template field review description when a workbook region is covered', () => {
  const onUpdateField = vi.fn();
  const onPickCoordinate = vi.fn();
  render(<ExperimentFieldReviewPanel
    fields={[{
      id: 'field-1', bindingId: 'binding-1', parentBindingId: '', name: '实验时间',
      groupName: '基本信息', regionId: 'region-1', fieldCode: 'experiment_time',
      dataPath: '/experiment/time', mappingKind: 'SCALAR', repeatAxis: 'UNKNOWN',
      locatorType: 'CELL_RANGE', standardMatchStatus: 'UNMATCHED', confirmed: false,
      confidence: 0.9, locator: { sheetId: 'sheet-1', labelRange: 'B2', address: 'C2' },
    }]}
    regions={[{
      id: 'region-1', bindingId: 'region-binding', dataPath: '/records',
      name: '重复记录区域', kind: 'ROW_TABLE', locatorType: 'TABLE_REGION',
      confirmed: false, locator: { sheetId: 'sheet-1', range: 'B1:G33' },
    }]}
    editable
    onUpdateField={onUpdateField}
    onConfirmRegion={vi.fn()}
    onFocusField={vi.fn()}
    onFocusRegion={vi.fn()}
    onAddField={vi.fn()}
    onDeleteField={vi.fn()}
    onPickCoordinate={onPickCoordinate}
    reviewTrigger={0}
  />);

  expect(screen.getByText('已识别 1 个字段，请核对名称和填写位置。')).toBeInTheDocument();
  expect(screen.queryByText(/已覆盖 0\/0 个物理区域/)).not.toBeInTheDocument();
  expect(screen.getByText(/sheet-1 · B1:G33/)).toBeInTheDocument();

  fireEvent.click(screen.getByRole('tab', { name: '字段结构' }));
  expect(screen.getByText('区域 1 · 字段 1 · 待确认 1')).toBeInTheDocument();
  expect(screen.getByText('普通字段：在空白位置输入名称后自动发现；明细字段请点击对应区域右侧“＋”。在数据区填写业务值不会创建字段。')).toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: /实验时间/ }));
  expect(screen.getByText('基本属性')).toBeInTheDocument();
  expect(screen.getByText('值类型')).toBeInTheDocument();
  expect(screen.getByText('Excel 位置')).toBeInTheDocument();
  expect(screen.getByText('工作表：sheet-1')).toBeInTheDocument();
  fireEvent.change(screen.getByDisplayValue('实验时间'), { target: { value: '实验时间新' } });
  expect(onUpdateField).toHaveBeenCalledWith('field-1', expect.objectContaining({ name: '实验时间新', confirmed: true }));
  fireEvent.click(screen.getByRole('button', { name: '从 Excel 选择填写位置' }));
  expect(onPickCoordinate).toHaveBeenCalledWith('field-1', 'address');
});
